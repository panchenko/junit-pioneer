package org.junitpioneer.jupiter;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static java.util.Spliterator.ORDERED;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.stream.Collectors.joining;
import static java.util.stream.StreamSupport.stream;
import static org.junit.platform.commons.util.AnnotationUtils.findAnnotation;
import static org.junit.platform.commons.util.AnnotationUtils.isAnnotated;

public class RepeatFailedTestExtension implements TestTemplateInvocationContextProvider, TestExecutionExceptionHandler {

	private static final Namespace NAMESPACE = Namespace.create("org", "codefx", "RepeatFailedTestExtension");

	@Override
	public boolean supportsTestTemplate(ExtensionContext context) {
		// the annotation only applies to methods
		Method testMethod = context.getRequiredTestMethod();
		return isAnnotated(testMethod, RepeatFailedTest.class);
	}

	@Override
	public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
			ExtensionContext context) {
		FailedTestRepeater repeater = repeaterFor(context);
		return stream(spliteratorUnknownSize(repeater, ORDERED), false);
	}

	@Override
	public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
		// this `context` (M) is a child of the context passed to `provideTestTemplateInvocationContexts` (T),
		// which means M's store content is invisible to T's store; this can be fixed by using T's store here
		ExtensionContext templateContext = context.getParent()
				.orElseThrow(() -> new IllegalStateException(
						"Extension context \"" + context + "\" should have a parent context."));
		repeaterFor(templateContext).failed(throwable);
	}

	private static FailedTestRepeater repeaterFor(ExtensionContext context) {
		Method repeatedTest = context.getRequiredTestMethod();
		return context
				.getStore(NAMESPACE)
				.getOrComputeIfAbsent(
						repeatedTest.toString(),
						__ -> FailedTestRepeater.createFor(repeatedTest),
						FailedTestRepeater.class);
	}

	private static class FailedTestRepeater implements Iterator<RepeatFailedTestInvocationContext> {

		private final int maxRepetitions;
		private int repetitionsSoFar;
		private final List<Throwable> exceptionsSoFar;

		private FailedTestRepeater(int maxRepetitions) {
			this.maxRepetitions = maxRepetitions;
			this.repetitionsSoFar = 0;
			this.exceptionsSoFar = new ArrayList<>();
		}

		static FailedTestRepeater createFor(Method repeatedTest) {
			int maxRepetitions = findAnnotation(repeatedTest, RepeatFailedTest.class)
					.map(RepeatFailedTest::value)
					.orElseThrow(IllegalStateException::new);
			return new FailedTestRepeater(maxRepetitions);
		}

		void failed(Throwable exception) throws Throwable {
			// TODO: throw if in "always fail" mode, log otherwise
			exceptionsSoFar.add(exception);

			boolean allRepetitionsFailed = exceptionsSoFar.size() == maxRepetitions;
			if (allRepetitionsFailed)
				failTest();
		}

		private void failTest() {
			StringWriter stringer = new StringWriter();
			writeFailureMessageInto(stringer);
			throw new AssertionError(stringer.toString());
		}

		private void writeFailureMessageInto(StringWriter stringer) {
			PrintWriter printer = new PrintWriter(stringer);
			printer.append("Each iteration of the repeated test failed.\n");
			for (int i = 0; i < exceptionsSoFar.size(); i++) {
				printer.append("\n---- EXECUTION #" + i + " ----\n");
				exceptionsSoFar.get(i).printStackTrace(printer);
			}
		}

		@Override
		public boolean hasNext() {
			// there's always at least one execution
			if (repetitionsSoFar == 0)
				return true;

			// if we caught an exception in each repetition, each repetition failed, including the previous one
			boolean previousFailed = repetitionsSoFar == exceptionsSoFar.size();
			boolean maxRepetitionsReached = repetitionsSoFar == maxRepetitions;
			return previousFailed && !maxRepetitionsReached;
		}

		@Override
		public RepeatFailedTestInvocationContext next() {
			repetitionsSoFar++;
			return new RepeatFailedTestInvocationContext();
		}

	}

}