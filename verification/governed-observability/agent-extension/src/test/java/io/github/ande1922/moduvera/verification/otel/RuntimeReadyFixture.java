package io.github.ande1922.moduvera.verification.otel;

/** Real-process fixture whose output proves application main was reached. */
public final class RuntimeReadyFixture {

    private RuntimeReadyFixture() {}

    public static void main(String[] ignoredArguments) {
        System.out.println("BUSINESS_READY");
    }
}
