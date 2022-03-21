package net.jqwik.engine.properties.state;

import java.util.*;
import java.util.concurrent.*;

import net.jqwik.api.*;
import net.jqwik.api.state.*;
import net.jqwik.api.statistics.*;
import net.jqwik.testing.*;

import static org.assertj.core.api.Assertions.*;

import static net.jqwik.api.Arbitraries.*;
import static net.jqwik.engine.properties.state.ChainsTestingHelper.*;

@PropertyDefaults(tries = 100)
class ChainArbitraryTests {

	@Example
	void deterministicChain(@ForAll Random random) {
		Arbitrary<Chain<Integer>> chains = Chains.chains(
			() -> 0,
			ignore -> just(ChainsTestingHelper.transformer(i -> i + 1, "+1"))
		).withMaxTransformations(10);

		Chain<Integer> chain = TestingSupport.generateFirst(chains, random);

		assertThat(collectAllValues(chain)).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		assertThat(chain.transformations()).containsExactly(
			"+1", "+1", "+1", "+1", "+1", "+1", "+1", "+1", "+1", "+1"
		);
	}

	@Property
	void chainWithSingleGenerator(@ForAll Random random) {
		TransformerProvider<Integer> growBelow100OtherwiseShrink = intSupplier -> {
			int last = intSupplier.get();
			if (last < 100) {
				return integers().between(0, 10).map(i -> t -> t + i);
			} else {
				return integers().between(1, 10).map(i -> t -> t - i);
			}
		};
		Arbitrary<Chain<Integer>> chains = Chains.chains(
			() -> 1,
			growBelow100OtherwiseShrink
		).withMaxTransformations(50);

		Shrinkable<Chain<Integer>> chainShrinkable = chains.generator(100).next(random);

		Chain<Integer> chain = chainShrinkable.value();

		assertThat(chain.maxTransformations()).isEqualTo(50);
		assertThat(chain.transformations()).hasSize(0);

		Iterator<Integer> iterator = chain.start();
		int last = 1;
		while (iterator.hasNext()) {
			int next = iterator.next();
			if (last < 100) {
				assertThat(next).isGreaterThanOrEqualTo(last);
			} else {
				assertThat(next).isLessThan(last);
			}
			last = next;
		}

		assertThat(chain.transformations()).hasSize(50);
	}

	@Property
	void chainWithSeveralGenerators(@ForAll Random random) {
		TransformerProvider<Integer> growBelow100otherwiseShrink = intSupplier -> {
			int last = intSupplier.get();
			if (last < 100) {
				return integers().between(0, 10).map(i -> t -> t + i);
			} else {
				return integers().between(1, 10).map(i -> t -> t - i);
			}
		};

		TransformerProvider<Integer> resetToValueBetween0andLastAbsolute = supplier -> {
			int last = supplier.get();
			return integers().between(0, Math.abs(last)).map(value -> (ignore -> value));
		};

		Arbitrary<Chain<Integer>> chains = Chains.chains(
			() -> 1,
			ignore -> Arbitraries.just(i -> i - 1),
			growBelow100otherwiseShrink,
			resetToValueBetween0andLastAbsolute
		).withMaxTransformations(50);

		Shrinkable<Chain<Integer>> chainShrinkable = chains.generator(100).next(random);

		Chain<Integer> chain = chainShrinkable.value();

		assertThat(chain.maxTransformations()).isEqualTo(50);
		assertThat(chain.transformations()).hasSize(0);

		Iterator<Integer> iterator = chain.start();
		iterator.forEachRemaining(ignore -> {});

		assertThat(chain.transformations()).hasSize(50);
	}

	@Property
	void chainCanBeRerunWithSameValues(@ForAll Random random) {
		Arbitrary<Chain<Integer>> chains = Chains.chains(
			() -> 1,
			ignore -> integers().between(0, 10).map(i -> t -> t + i)
		);

		Shrinkable<Chain<Integer>> chainShrinkable = chains.generator(100).next(random);

		Chain<Integer> chain = chainShrinkable.value();

		List<Integer> values1 = collectAllValues(chain);
		List<Integer> values2 = collectAllValues(chain);
		assertThat(values2).isEqualTo(values1);
	}

	@Property
	@StatisticsReport(onFailureOnly = true)
	void useFrequenciesToChooseTransformers(@ForAll Random random) {

		TransformerProvider<Integer> just42 = ignore -> Arbitraries.just(t -> 42);
		TransformerProvider<Integer> just1 = ignore -> Arbitraries.just(t -> 1);
		TransformerProvider<Integer> just2 = ignore -> Arbitraries.just(t -> 2);

		Arbitrary<Chain<Integer>> chains = Chains.chains(
			() -> 0,
			Tuple.of(0, just42),
			Tuple.of(1, just1),
			Tuple.of(4, just2)
		).withMaxTransformations(10);

		Shrinkable<Chain<Integer>> chainShrinkable = chains.generator(100).next(random);
		Chain<Integer> chain = chainShrinkable.value();

		for (Integer value : chain) {
			Statistics.collect(value);
		}

		Statistics.coverage(checker -> {
			checker.check(42).count(c -> c == 0);
			checker.check(0).percentage(p -> p >= 9 && p <= 10); // Always 1 of 11
			checker.check(1).percentage(p -> p > 0 && p < 25);
			checker.check(2).percentage(p -> p > 65);
		});
	}

	@Property
	void transformerThatReturnsNullIsIgnored(@ForAll Random random) {
		TransformerProvider<List<Integer>> addRandomIntToList =
			ignore -> integers().between(0, 10)
								.map(i -> l -> {
									l.add(i);
									return l;
								});

		TransformerProvider<List<Integer>> removeFirstElement = supplier -> {
			List<Integer> last = supplier.get();
			if (last.isEmpty()) {
				return null;
			}
			return Arbitraries.just(l -> {
				l.remove(0);
				return l;
			});
		};

		ChainArbitrary<List<Integer>> chains = Chains.chains(
			ArrayList::new,
			addRandomIntToList,
			removeFirstElement
		).withMaxTransformations(13);

		Shrinkable<Chain<List<Integer>>> chainShrinkable = chains.generator(100).next(random);

		Chain<List<Integer>> chain = chainShrinkable.value();
		Iterator<List<Integer>> iterator = chain.iterator();
		iterator.next(); // Ignore initial state

		List<Integer> last = new ArrayList<>();
		while (iterator.hasNext()) {
			int lastSize = last.size();
			List<Integer> next = iterator.next();
			assertThat(lastSize).isNotEqualTo(next.size());
			last = next;
		}
		assertThat(chain.transformations()).hasSize(13);
	}

	@Example
	void stopGenerationIfOnlyNullArbitrariesAreAvailable(@ForAll Random random) {
		Arbitrary<Chain<Integer>> chains = Chains.chains(
			() -> 1,
			ignore -> null
		).withMaxTransformations(50);

		Chain<Integer> chain = chains.generator(100).next(random).value();

		assertThatThrownBy(() -> {
			chain.start().forEachRemaining(ignore -> {});
		}).isInstanceOf(JqwikException.class);
	}

	@Property(tries = 5)
	void concurrentlyIteratingChainProducesSameResult(@ForAll Random random) throws Exception {
		Arbitrary<Chain<Integer>> chains = Chains.chains(
			() -> 1,
			ignore -> Arbitraries.integers().between(1, 10).map(i -> t -> t + i)
		).withMaxTransformations(30);

		Chain<Integer> chain = chains.generator(100).next(random).value();

		Callable<List<Integer>> allValuesCallable = () -> {
			List<Integer> values = new ArrayList<>();
			for (Integer value : chain) {
				Thread.sleep(random.nextInt(10));
				values.add(value);
			}
			return values;
		};

		ExecutorService service = Executors.newFixedThreadPool(3);
		List<Future<List<Integer>>> futures = service.invokeAll(
			Arrays.asList(allValuesCallable, allValuesCallable, allValuesCallable)
		);

		List<Integer> values1 = futures.get(0).get();
		List<Integer> values2 = futures.get(1).get();
		List<Integer> values3 = futures.get(2).get();

		assertThat(values1).hasSize(30 + 1);
		assertThat(values1).isEqualTo(values2);
		assertThat(values1).isEqualTo(values3);
	}

	private <T> List<T> collectAllValues(Chain<T> chain) {
		List<T> values = new ArrayList<>();
		for (T i : chain) {
			values.add(i);
		}
		return values;
	}

	@Group
	@PropertyDefaults(tries = 100)
	class Shrinking {

		@Property
		void shrinkChainWithoutStateAccessToEnd(@ForAll Random random) {
			Arbitrary<Chain<Integer>> chains = Chains.chains(
				() -> 1,
				ignore -> integers().between(0, 10).map(i -> t -> t + i)
			).withMaxTransformations(5);

			TestingFalsifier<Chain<Integer>> falsifier = chain -> {
				for (Integer integer : chain) {
					// consume iterator
				}
				return false;
			};
			Chain<Integer> chain = ShrinkingSupport.falsifyThenShrink(chains, random, falsifier);
			assertThat(chain.transformations()).hasSize(chain.maxTransformations());
			assertThat(collectAllValues(chain)).containsExactly(1, 1);
		}

		@Property
		void removeNullTransformersDuringShrinking(@ForAll Random random) {
			Transformer<Integer> addOne = transformer(t -> t + 1, "addOne");
			Transformer<Integer> doNothing = transformer(t -> t, "doNothing");

			Arbitrary<Chain<Integer>> chains = Chains.chains(
				() -> 1,
				ignore -> Arbitraries.just(addOne),
				ignore -> Arbitraries.just(doNothing)
			).withMaxTransformations(20); // Size must be large enough to have at least a single addOne transformer

			TestingFalsifier<Chain<Integer>> falsifier = chain -> {
				int last = 1;
				for (Integer value : chain) {
					last = value;
				}
				return last <= 1;
			};
			Chain<Integer> chain = ShrinkingSupport.falsifyThenShrink(chains, random, falsifier);
			assertThat(chain.transformations()).hasSize(chain.maxTransformations());
			assertThat(collectAllValues(chain)).containsExactly(1, 2);
		}

		@Property
		void fullyShrinkTransformersWithoutStateAccess(@ForAll Random random) {
			Arbitrary<Chain<Integer>> chains = Chains.chains(
				() -> 0,
				ignore -> integers().between(1, 5).map(i -> transformer(t -> t + i, "add" + i))
			).withMaxTransformations(10);

			TestingFalsifier<Chain<Integer>> falsifier = chain -> {
				int last = 1;
				for (Integer value : chain) {
					last = value;
				}
				return last < 3;
			};
			Chain<Integer> chain = ShrinkingSupport.falsifyThenShrink(chains, random, falsifier);

			// There are 4 possible "smallest" chains
			assertThat(collectAllValues(chain)).isIn(
				Arrays.asList(0, 3),
				Arrays.asList(0, 1, 3),
				Arrays.asList(0, 2, 3),
				Arrays.asList(0, 1, 2, 3)
			);
		}

		@Property
		void shrinkChainWithStateAccess(@ForAll Random random) {
			Arbitrary<Chain<Integer>> chains = Chains.chains(
				() -> 1,
				supplier -> {
					supplier.get(); // To make shrinkable think the last value is being used
					return integers().between(0, 10).map(i -> t -> t + i);
				}
			).withMaxTransformations(10);

			TestingFalsifier<Chain<Integer>> falsifier = chain -> {
				int count = 0;
				int sum = 0;
				for (Integer value : chain) {
					sum += value;
					count++;
					if (count >= 5 && sum >= 5) {
						return false;
					}
				}
				return true;
			};
			Chain<Integer> chain = ShrinkingSupport.falsifyThenShrink(chains, random, falsifier);
			assertThat(chain.transformations()).hasSize(chain.maxTransformations());
			assertThat(collectAllValues(chain)).containsExactly(1, 1, 1, 1, 1);
		}

		@Property
		void shrinkChainWithMixedAccess(@ForAll Random random) {
			Arbitrary<Chain<Integer>> chains = Chains.chains(
				() -> 0,
				supplier -> {
					int current = Math.abs(supplier.get());
					if (current > 100) {
						return just(transformer(t -> t / 2, "half"));
					} else {
						return integers().between(current, current * 2).map(i -> transformer(t -> t + i, "add-" + i));
					}
				},
				ignore -> Arbitraries.just(transformer(t -> t - 1, "minus-1"))
			).withMaxTransformations(10);

			TestingFalsifier<Chain<Integer>> falsifier = chain -> {
				for (Integer value : chain) {
					if (value > 20) {
						return false;
					}
				}
				return true;
			};
			Chain<Integer> chain = ShrinkingSupport.falsifyThenShrink(chains, random, falsifier);

			List<Integer> series = collectAllValues(chain);
			assertThat(chain.transformations()).hasSize(chain.maxTransformations());
			assertThat(series.get(series.size() - 1))
				.describedAs("Last element of %s", series)
				.isBetween(21, 40); // It's either 21 or the double of the but-last value
			assertThat(series.get(series.size() - 2))
				.describedAs("But-last element of %s", series)
				.isLessThanOrEqualTo(20);
		}

		@Property
		void whenShrinkingTryToRemoveTransformersWithStateAccess(@ForAll Random random) {
			Arbitrary<Chain<Integer>> chains = Chains.chains(
				() -> 0,
				supplier -> {
					int current = supplier.get(); // access state
					return Arbitraries.just(transformer(ignore -> current + 1, "plus-1"));
				},
				ignore -> Arbitraries.just(transformer(t -> t + 2, "plus-2"))
			).withMaxTransformations(20);

			RandomGenerator<Chain<Integer>> generator = chains.generator(100, false);
			Shrinkable<Chain<Integer>> shrinkable = TestingSupport.generateUntil(
				generator,
				random,
				this::hasAtLeastFivePlus2Transformers
			);

			TestingFalsifier<Chain<Integer>> falsifier = chain -> {
				for (Integer value : chain) {
					if (value > 9) {
						return false;
					}
				}
				return true;
			};
			Chain<Integer> shrunkChain = ShrinkingSupport.shrink(shrinkable, falsifier, null);

			List<Integer> series = collectAllValues(shrunkChain);
			assertThat(series).containsExactly(0, 2, 4, 6, 8, 10);
			assertThat(shrunkChain.maxTransformations()).isEqualTo(5);
		}

		private boolean hasAtLeastFivePlus2Transformers(Chain<Integer> c) {
			for (Integer value : c) {} // consume chain
			long countPlusTwoBefore = c.transformations().stream().filter(s -> s.equals("plus-2")).count();
			return countPlusTwoBefore >= 5;
		}

	}
}
