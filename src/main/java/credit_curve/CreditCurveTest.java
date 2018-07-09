package credit_curve;

import static com.opengamma.strata.basics.currency.Currency.USD;
import static com.opengamma.strata.basics.date.DayCounts.ACT_360;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Formatter;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.opengamma.strata.basics.ReferenceData;
import com.opengamma.strata.basics.StandardId;
import com.opengamma.strata.basics.date.BusinessDayAdjustment;
import com.opengamma.strata.basics.date.BusinessDayConventions;
import com.opengamma.strata.basics.date.DayCounts;
import com.opengamma.strata.basics.date.DaysAdjustment;
import com.opengamma.strata.basics.date.HolidayCalendarId;
import com.opengamma.strata.basics.date.HolidayCalendarIds;
import com.opengamma.strata.basics.schedule.Frequency;
import com.opengamma.strata.basics.schedule.PeriodicSchedule;
import com.opengamma.strata.basics.schedule.RollConventions;
import com.opengamma.strata.basics.schedule.StubConvention;
import com.opengamma.strata.collect.array.DoubleArray;
import com.opengamma.strata.data.ImmutableMarketData;
import com.opengamma.strata.data.ImmutableMarketDataBuilder;
import com.opengamma.strata.market.curve.CurveName;
import com.opengamma.strata.market.curve.IsdaCreditCurveDefinition;
import com.opengamma.strata.market.curve.NodalCurve;
import com.opengamma.strata.market.curve.node.CdsIsdaCreditCurveNode;
import com.opengamma.strata.market.observable.QuoteId;
import com.opengamma.strata.pricer.credit.AccrualOnDefaultFormula;
import com.opengamma.strata.pricer.credit.ConstantRecoveryRates;
import com.opengamma.strata.pricer.credit.FastCreditCurveCalibrator;
import com.opengamma.strata.pricer.credit.ImmutableCreditRatesProvider;
import com.opengamma.strata.pricer.credit.IsdaCreditDiscountFactors;
import com.opengamma.strata.pricer.credit.LegalEntitySurvivalProbabilities;
import com.opengamma.strata.product.credit.type.CdsConvention;
import com.opengamma.strata.product.credit.type.CdsTemplate;
import com.opengamma.strata.product.credit.type.DatesCdsTemplate;
import com.opengamma.strata.product.credit.type.ImmutableCdsConvention;

import io.vavr.collection.List;


public class CreditCurveTest {

	public static double[] getStrataSurvivalProbabilities(double spread, double recoveryRate,
			LocalDate startDate, LocalDate endDate, Frequency frequency, LocalDate valDate) {

		ReferenceData REF_DATA = ReferenceData.standard();

		HolidayCalendarId accrualCal = HolidayCalendarIds.NYFD.combinedWith(HolidayCalendarIds.GBLO);
		PeriodicSchedule definition = PeriodicSchedule.of(startDate, endDate, frequency,
				BusinessDayAdjustment.of(BusinessDayConventions.MODIFIED_FOLLOWING, accrualCal),
				StubConvention.SHORT_INITIAL, RollConventions.NONE);

		ImmutableList<LocalDate> scheduleDates = definition.createUnadjustedDates();
		scheduleDates = ImmutableList.copyOf(List.ofAll(scheduleDates.asList()).filter(d -> d.isAfter(valDate))
				.filter(d -> d.isAfter(startDate)).asJava());

		DoubleArray time = DoubleArray
				.copyOf(List.ofAll(scheduleDates).map(d -> DayCounts.ACT_360.yearFraction(valDate, d)).asJava());
		// Discount factors don't matter since curve has constant spread and recovery rate
		DoubleArray discounts = DoubleArray
				.copyOf(List.ofAll(time.toArrayUnsafe()).map(t -> 1-Math.exp(-1d/t)/4d).asJava());

		IsdaCreditDiscountFactors df = IsdaCreditDiscountFactors.of(USD, valDate, CurveName.of("discount_usd"), time,
				discounts, ACT_360);

		StandardId LEGAL_ENTITY = StandardId.of("OG", "ABC");

		ImmutableCreditRatesProvider ratesProvider = ImmutableCreditRatesProvider.builder().valuationDate(valDate)
				.discountCurves(ImmutableMap.of(USD, df))
				.recoveryRateCurves(
						ImmutableMap.of(LEGAL_ENTITY, ConstantRecoveryRates.of(LEGAL_ENTITY, valDate, recoveryRate)))
				.creditCurves(ImmutableMap.of()).build();

		ImmutableMarketDataBuilder builderCredit = ImmutableMarketData.builder(valDate);
		List<CdsIsdaCreditCurveNode> nodes = List.empty();

		BusinessDayAdjustment BUS_ADJ = BusinessDayAdjustment.of(BusinessDayConventions.MODIFIED_FOLLOWING, accrualCal);
		DaysAdjustment CDS_SETTLE_STD = DaysAdjustment.builder().calendar(accrualCal).days(0)
				.adjustment(BusinessDayAdjustment.of(BusinessDayConventions.FOLLOWING,  accrualCal)).build();

		for (int i = 0; i < scheduleDates.size(); ++i) {
			CdsConvention conv = ImmutableCdsConvention.builder().name("conv").currency(USD).dayCount(ACT_360)
					.paymentFrequency(frequency).businessDayAdjustment(BUS_ADJ).settlementDateOffset(CDS_SETTLE_STD)
					.rollConvention(RollConventions.NONE).build();
			CdsTemplate temp = DatesCdsTemplate.of(startDate, scheduleDates.get(i), conv);
			QuoteId id = QuoteId.of(StandardId.of("OG", scheduleDates.get(i).toString()));
			nodes = nodes.append(CdsIsdaCreditCurveNode.ofQuotedSpread(temp, id, LEGAL_ENTITY, 0d));
			builderCredit.addValue(id, spread);
		}
		ImmutableMarketData marketData = builderCredit.build();

		IsdaCreditCurveDefinition curveDefinition = IsdaCreditCurveDefinition.of(CurveName.of("cc"), USD, valDate,
				ACT_360, nodes.asJava(), true, false);

		FastCreditCurveCalibrator BUILDER_ISDA = new FastCreditCurveCalibrator(AccrualOnDefaultFormula.ORIGINAL_ISDA);
		LegalEntitySurvivalProbabilities survivalProbs = BUILDER_ISDA.calibrate(curveDefinition, marketData,
				ratesProvider, REF_DATA);

		NodalCurve nc = ((IsdaCreditDiscountFactors) survivalProbs.getSurvivalProbabilities()).getCurve();
		double[] result = new double[nc.getParameterCount()];
		for (int i = 0; i < nc.getParameterCount(); i++) {
			LocalDate date = LocalDate.parse(nc.getParameterMetadata(i).getLabel(), DateTimeFormatter.ISO_DATE);
			result[i] = survivalProbs.survivalProbability(date);
			//System.out.println("Survival probability for " + date + " = " + survivalProbs.survivalProbability(date));
		}

		return result;
	}

	/**
	 * Get the survival probabilities by using the standard formula:
	 * Pr = exp{-S*t / (1 - RR)}
	 */
	public static double[] getFormulaSurvivalProbabilities(double spread, double recoveryRate, LocalDate startDate, 
			LocalDate endDate, Frequency frequency, LocalDate valDate) {

		HolidayCalendarId accrualCal = HolidayCalendarIds.NYFD.combinedWith(HolidayCalendarIds.GBLO);
		PeriodicSchedule definition = PeriodicSchedule.of(startDate, endDate, frequency,
				BusinessDayAdjustment.of(BusinessDayConventions.MODIFIED_FOLLOWING, accrualCal),
				StubConvention.SHORT_INITIAL, RollConventions.NONE);

		ImmutableList<LocalDate> scheduleDates = definition.createUnadjustedDates();
		scheduleDates = ImmutableList.copyOf(List.ofAll(scheduleDates.asList()).filter(d -> d.isAfter(valDate))
				.filter(d -> d.isAfter(startDate)).asJava());

		DoubleArray time = DoubleArray
				.copyOf(List.ofAll(scheduleDates).map(d -> DayCounts.ACT_360.yearFraction(valDate, d)).asJava());

		return time.map(t -> getSurvivalProbability(t, spread, recoveryRate)).toArray();

	}

	public static double getSurvivalProbability(double t, double spread, double recoveryRate) {
		return Math.exp((-spread*t) / (1 - recoveryRate));
	}



	public static void main( String[] args ) {

		double spread = 0.02;
		double recoveryRate = 0.7;
		LocalDate valDate = LocalDate.of(2018, 7, 5);
		LocalDate startDate = LocalDate.of(2018, 7, 5);
		LocalDate endDate = LocalDate.of(2023, 7, 5);
		// Using frequency of 1M produces different results in strata. 
		// Third result of 1M frequency should be the same as first result of 3M frequency but is not.
		Frequency frequency = Frequency.P3M;

		double[] strataProbs = getStrataSurvivalProbabilities(spread, recoveryRate, startDate, endDate, frequency, valDate);
		double[] formulaProbs = getFormulaSurvivalProbabilities(spread, recoveryRate, startDate, endDate, frequency, valDate);

		double[] differences = new double[strataProbs.length];

		StringBuilder sb = new StringBuilder();
		Formatter formatter = new Formatter(sb);

		formatter.format("%n%15s %15s %15s", "Strata", "Formula", "Difference");
		for(int i = 0; i < strataProbs.length; i++) {
			differences[i] = strataProbs[i] - formulaProbs[i];
			formatter.format("%n%15.5f %15.5f %15.5f", strataProbs[i], formulaProbs[i], differences[i]);
		}
		formatter.close();
		System.out.println(sb.toString());

	}
}
