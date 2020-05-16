package net.jqwik.api.statistics;

import java.math.*;
import java.util.*;
import java.util.stream.*;

import org.apiguardian.api.*;

import net.jqwik.api.*;
import net.jqwik.api.Tuple.*;

import static org.apiguardian.api.API.Status.*;

/**
 * A specialized type of {@linkplain Histogram} to divide collected numbers
 * into range-based clusters for display in a histogram.
 */
@API(status = EXPERIMENTAL, since = "1.3.0")
public class NumberRangeHistogram extends Histogram {

	/**
	 * Determines the number of buckets into which the full range of collected
	 * numbers will be clustered.
	 *
	 * @return A number greater than 0
	 */
	protected int buckets() {
		return 20;
	}

	/**
	 * Does not make sense to override since these labels won't be used anyway
	 */
	@Override
	final protected String label(final StatisticsEntry entry) {
		return "not used";
	}

	/**
	 * Does not make sense to override since order does not matter for clustering anyway
	 */
	@Override
	final protected Comparator<? super StatisticsEntry> comparator() {
		return (left, right) -> 0;
	}

	/**
	 * Does not make sense to override because this has the number range functionality
	 */
	@Override
	final protected List<Bucket> cluster(final List<StatisticsEntry> entries) {
		Tuple2<BigInteger, BigInteger> minMax = minMax(entries);
		BigInteger min = minMax.get1();
		BigInteger max = minMax.get2();

		List<Tuple2<BigInteger, Bucket>> topsAndBuckets = topsAndBuckets(min, max);

		for (StatisticsEntry entry : entries) {
			Bucket bucket = findBucket(topsAndBuckets, value(entry));
			bucket.addCount(entry.count());
		}

		return topsAndBuckets.stream().map(Tuple2::get2).collect(Collectors.toList());
	}

	private Bucket findBucket(List<Tuple2<BigInteger, Bucket>> topsAndBuckets, BigDecimal value) {
		for (int i = 0; i < topsAndBuckets.size(); i++) {
			Tuple2<BigInteger, Bucket> topAndBucket = topsAndBuckets.get(i);
			BigInteger top = topAndBucket.get1();
			if (value.compareTo(new BigDecimal(top)) < 0) {
				return topAndBucket.get2();
			}
			if (i == topsAndBuckets.size() - 1) {
				return topAndBucket.get2();
			}
		}
		throw new RuntimeException(String.format("No bucket found for value [%s]", value));
	}

	private List<Tuple2<BigInteger, Bucket>> topsAndBuckets(final BigInteger min, final BigInteger max) {
		BigInteger range = max.subtract(min);
		BigInteger numberOfBuckets = BigInteger.valueOf(buckets());
		BigInteger step = range.divide(numberOfBuckets);
		BigInteger remainder = range.remainder(numberOfBuckets);
		if (remainder.compareTo(BigInteger.ZERO) != 0) {
			step = step.add(BigInteger.ONE);
		}

		List<Tuple2<BigInteger, Bucket>> topsAndBuckets = new ArrayList<>();
		BigInteger left = min;
		for (BigInteger index = min.add(step); index.compareTo(max) < 0; index = index.add(step)) {
			String label = String.format("[%s..%s[", left, index);
			topsAndBuckets.add(Tuple.of(index, new Bucket(label)));
			left = index;
		}
		String label = String.format("[%s..%s]", left, max);
		topsAndBuckets.add(Tuple.of(max, new Bucket(label)));
		return topsAndBuckets;
	}

	private Tuple2<BigInteger, BigInteger> minMax(final List<StatisticsEntry> entries) {
		BigDecimal min = null;
		BigDecimal max = null;

		try {
			for (StatisticsEntry entry : entries) {
				BigDecimal value = value(entry);
				if (min == null || value.compareTo(min) < 0) {
					min = value;
				}
				if (max == null || value.compareTo(max) > 0) {
					max = value;
				}
			}
		} catch (NumberFormatException numberFormatException) {
			String message = "In number range histograms each entry must have exactly one number value";
			throw new JqwikException(message);
		}

		BigInteger maxBigInteger = max.setScale(0, BigDecimal.ROUND_UP).toBigInteger();
		return Tuple.of(min.toBigInteger(), maxBigInteger);
	}

	private BigDecimal value(final StatisticsEntry entry) {
		return new BigDecimal(entry.name());
	}

}
