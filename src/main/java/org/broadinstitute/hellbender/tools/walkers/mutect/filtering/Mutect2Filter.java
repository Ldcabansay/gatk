package org.broadinstitute.hellbender.tools.walkers.mutect.filtering;

import htsjdk.variant.variantcontext.VariantContext;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.broadinstitute.hellbender.engine.ReferenceContext;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Base class for all Mutect2Filters
 */
public abstract class Mutect2Filter {
    // by default do nothing, but we may override to allow some filters to learn their parameters in the first pass of {@link FilterMutectCalls}
    protected void accumulateDataForLearning(final VariantContext vc, final ErrorProbabilities errorProbabilities, final Mutect2FilteringEngine filteringEngine) { }
    protected void clearAccumulatedData() { }
    protected void learnParameters() { }
    protected void learnParametersAndClearAccumulatedData() {
        learnParameters();
        clearAccumulatedData();
    }

    public abstract ErrorType errorType();
    public abstract String filterName();
    public abstract Optional<String> phredScaledPosteriorAnnotationName();
    protected abstract List<String> requiredInfoAnnotations();

    /**
     * Should be overridden by the implementing class to return the probability that the allele should
     * filtered out. For filters that only apply at the site level, the same probability should be
     * returned for every alt allele
     * @param vc
     * @param filteringEngine
     * @param referenceContext
     * @return The probability that each alternate allele should be filtered out. This list should NOT include data for the reference allele
     */
    public abstract List<Double> errorProbabilities(final VariantContext vc, final Mutect2FilteringEngine filteringEngine, ReferenceContext referenceContext);

    // weighted median -- what's the lowest posterior probability that accounts for samples with half of the total alt depth
    protected static double weightedMedianPosteriorProbability(List<ImmutablePair<Integer, Double>> depthsAndPosteriors) {
        final int totalAltDepth = depthsAndPosteriors.stream().mapToInt(ImmutablePair::getLeft).sum();

        // sort from lowest to highest posterior probability of artifact
        depthsAndPosteriors.sort(Comparator.comparingDouble(p -> p.getRight()));

        int cumulativeAltCount = 0;

        for (final ImmutablePair<Integer, Double> pair : depthsAndPosteriors) {
            cumulativeAltCount += pair.getLeft();
            if (cumulativeAltCount * 2 >= totalAltDepth) {
                return pair.getRight();
            }
        }
        return 0;
    }

}
