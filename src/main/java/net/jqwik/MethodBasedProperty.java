
package net.jqwik;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.gen5.commons.util.ReflectionUtils;
import org.junit.gen5.commons.util.StringUtils;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;
import net.jqwik.api.MissingGeneratorConstructor;
import net.jqwik.api.ParameterConstraintViolation;

public class MethodBasedProperty implements ExecutableProperty {

	private static final Logger LOG = Logger.getLogger(MethodBasedProperty.class.getName());

	private final Class<?> testClass;
	private final Method propertyMethod;
	private final Map<Class<?>, Class<? extends Generator>> generatorClasses = new HashMap<>();
	private final Random random;

	private int numberOfTries = 100;

	public MethodBasedProperty(Class<?> testClass, Method propertyMethod, long randomSeed) {
		this.testClass = testClass;
		this.propertyMethod = propertyMethod;
		this.random = new Random(randomSeed);
		generatorClasses.put(Integer.class, IntegerGenerator.class);
		generatorClasses.put(int.class, IntegerGenerator.class);
	}

	@Override
	public String name() {
		return propertyMethod.getName();
	}

	@Override
	public void evaluate() {
		Generator[] parameterGenerators = getParameterGenerators();
		for (int i = 0; i < numberOfTries; i++) {


			boolean propertyResult;
			Object[] parameters;

			int maxMisses = numberOfTries * 10;
			int countMisses = 0;
			while(true) {
				parameters = nextParameters(parameterGenerators);
				try {
					propertyResult = !evaluate(parameters);
					break;
				} catch (ParameterConstraintViolation pcv) {
					countMisses++;
					if (countMisses >= maxMisses)
						throw new TestAbortedException("Too many misses trying to create parameters.");
					continue;
				}
			}

			if (propertyResult) {
				Object[] shrinkedParameters = shrinkParameters(parameters, parameterGenerators);
				String message = String.format("Failed with parameters: [%s]",
					parameterDescription(shrinkedParameters));
				throw new AssertionFailedError(message);
			}
		}
	}

	private Object[] shrinkParameters(Object[] parameters, Generator[] parameterGenerators) {
		List<Object> lastFailingSet = Arrays.asList(parameters);

		for (int i = 0; i < parameters.length; i++) {
			final int index = i;
			Object currentValue = parameters[i];
			Generator currentGenerator = parameterGenerators[i];
			Function<Object, Boolean> evaluator = value -> {
				List<Object> params = new ArrayList<>(lastFailingSet);
				params.set(index, value);
				return evaluate(params.toArray());
			};
			Object shrinkedValue = shrinkValue(currentValue, currentGenerator, evaluator);
			lastFailingSet.set(i, shrinkedValue);
		}

		return lastFailingSet.toArray();
	}

	private Object shrinkValue(Object currentValue, Generator currentGenerator, Function<Object, Boolean> evaluator) {
		return new Shrinker(currentGenerator).shrink(currentValue, evaluator);
	}

	private boolean evaluate(Object[] parameters) {
		LOG.finest(() -> String.format("Run method '%s' with parameters: [%s]", methodDescription(),
			parameterDescription(parameters)));
		Object testInstance = null;
		if (!ReflectionUtils.isStatic(propertyMethod))
			testInstance = ReflectionUtils.newInstance(testClass);
		return (boolean) ReflectionUtils.invokeMethod(propertyMethod, testInstance, parameters);
	}

	private String parameterDescription(Object[] parameters) {
		Stream<String> parameterStrings = Arrays.stream(parameters).map(Object::toString);
		return StringUtils.join(parameterStrings, ",");
	}

	private String methodDescription() {
		return propertyMethod.getName();
	}

	private Object[] nextParameters(Generator[] parameterGenerators) {
		return Arrays.stream(parameterGenerators) //
		.map(generator -> generator.generate()) //
		.toArray();
	}

	private Generator[] getParameterGenerators() {
		return Arrays.stream(propertyMethod.getParameters()) //
		.map(parameter -> {
			Generator generator = createGenerator(parameter).orElseThrow(() -> {
				String message = String.format("Cannot generate values for paramters of type '%s'",
					parameter.getType());
				return new RuntimeException(message);
			});
			return generator;
		}).collect(Collectors.toList()).toArray(new Generator[0]);
	}

	private Optional<Generator> createGenerator(Parameter parameter) {
		Class<? extends Generator> generatorClass = generatorClasses.get(parameter.getType());
		if (generatorClass == null)
			return Optional.empty();
		try {
			Generator generator = ReflectionUtils.newInstance(generatorClass, random);
			return Optional.of(generator);
		} catch (Exception e) {
			if (e.getClass() == NoSuchMethodException.class)
				throw new MissingGeneratorConstructor(generatorClass);
			throw e;
		}
	}
}
