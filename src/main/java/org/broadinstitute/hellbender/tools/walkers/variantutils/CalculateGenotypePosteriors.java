package org.broadinstitute.hellbender.tools.walkers.variantutils;

import htsjdk.variant.variantcontext.GenotypesContext;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.VariantContextUtils;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.utils.io.IOUtils;
import picard.cmdline.programgroups.VariantEvaluationProgramGroup;
import org.broadinstitute.hellbender.engine.*;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.utils.samples.*;
import org.broadinstitute.hellbender.utils.variant.*;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Calculate genotype posterior probabilities given family and/or known population genotypes
 *
 * <p>
 * The tool calculates the posterior genotype probability for each sample genotype in a given VCF format callset.
 * The input variants must present genotype likelihoods generated by HaplotypeCaller, UnifiedGenotyper or other
 * source that provides unbiased genotype likelihoods.</p>
 *
 * <p>
 * The tool can use priors from three different data sources: (i) one or more supporting germline population callsets
 * with specific annotation(s) if supplied , (ii) the pedigree for a trio if supplied and if the trio is represented
 * in the callset under refinement, and/or (iii) the allele counts of the callset samples themselves given at least
 * ten samples. It is possible to deactivate the contribution of the callset samples with the --ignore-input-samples
 * flag.
 * </p>
 *
 * <p>
 * For more background information and for mathematical details, see GATK forum article at
 * https://software.broadinstitute.org/gatk/documentation/article?id=11074.
 * Additional GATK mathematical notes are presented as whitepapers in the <i>gatk</i> GitHub repository docs section
 * at https://github.com/broadinstitute/gatk/tree/master/docs.
 * </p>
 *
 * <h3>Inputs</h3>
 * <p>
 *     <ul>
 *         <li>A VCF with genotype likelihoods, and optionally genotypes, AC/AN fields, or MLEAC/AN fields.
 *         The tool will use MLEAC if available or AC if MLEAC is not provided. AN is also required unless genotypes are
 *         provided for all samples.</li>
 *         <li>(Optional) A PED pedigree file containing the description of the relationships between individuals. The
 *         tool considers only trio groups. A trio consists of mother-father-child.</li>
 *     </ul>
 * </p>
 *
 * <p>
 * Optionally, a collection of VCFs can be provided for the purpose of informing population allele frequency priors. Each of
 * these resource VCFs must satisfy at least one of the following requirement sets:
 * </p>
 * <ul>
 *     <li>AC field and AN field</li>
 *     <li>MLEAC field and AN field</li>
 *     <li>Genotypes</li>
 * </ul>
 * </p>
 *
 * <h3>Output</h3>
 * <p>A new VCF with the following information:</p>
 * <ul>
 *     <li>Genotype posteriors added to the FORMAT fields ("PP")</li>
 *     <li>Genotypes and GQ assigned according to these posteriors (note that the original genotype and GQ may change)</li>
 *     <li>Per-site genotype priors added to the INFO field ("PG")</li>
 *     <li>(Optional) Per-site, per-trio joint likelihoods (JL) and joint posteriors (JP) given as Phred-scaled probability
 *  of all genotypes in the trio being correct based on the PLs for JL and the PPs for JP. These annotations are added to
 *  the FORMAT fields.</li>
 * </ul>
 *
 * <h3>Notes</h3>
 * <p>
 * By default, priors will be applied to each variant separately, provided each variant features data from at least
 * 10 called samples (no-calls do not count). SNP sites in the input callset that have a SNP at the matching site in
 * the supporting VCF will have priors applied based on the AC from the supporting samples and the input callset
 * unless the --ignore-input-samples flag is used. If a site is not called in the supporting VCF, priors will be
 * applied using the discovered AC from the input samples unless the --discovered-allele-count-priors-off flag is used.
 * For any non-SNP sites in the input callset, flat priors are applied.
 * </p>
 *
 * <p>
 * For versions of the tool 4.0.5.0+, the tool appropriately applies priors to indels.
 * </p>
 *
 * <p>
 * If applying family priors, only diploid family genotypes are supported. In addition, family priors only apply to
 * trios represented in both a supplied pedigree and in the callset under refinement. Note, if the pedigree is
 * incomplete, the tools skips calculating family priors. In this case, and in the absence of other refinement, the
 * results will be identical to the input.
 * </p>
 *
 * <h3>Usage examples</h3>
 *
 * <h4>Refine genotypes based on the discovered allele frequency in an input VCF containing many samples</h4>
 * <pre>
 * gatk --java-options "-Xmx4g" CalculateGenotypePosteriors \
 *   -V multisample_input.vcf.gz \
 *   -O output.vcf.gz
 * </pre>
 *
 * <h4>Inform the genotype assignment of a single sample using the 1000G phase 3 samples</h4>
 * <pre>
 * gatk --java-options "-Xmx4g" CalculateGenotypePosteriors \
 *   -V sample_input.vcf.gz \
 *   -O sample_output.1000G_PPs.vcf.gz \
 *   -supporting 1000G.phase3.integrated.sites_only.no_MATCHED_REV.hg38.vcf.gz
 * </pre>
 *
 * <h4>Apply only family priors to a callset</h4>
 * <pre>
 * gatk --java-options "-Xmx4g" CalculateGenotypePosteriors \
 *   -V input.vcf.gz \
 *   -O output.vcf.gz \
 *   -ped family.ped \
 *   --skip-population-priors
 * </pre>
 *
 * <h4>Apply frequency and HWE-based priors to the genotypes of a family without including the family allele counts
 * in the allele frequency estimates</h4>
 * <pre>
 * gatk --java-options "-Xmx4g" CalculateGenotypePosteriors \
 *   -V input.vcf.gz \
 *   -O output.vcf.gz \
 *   --ignore-input-samples
 * </pre>
 *
 * <h4>Calculate the posterior genotypes of a callset, and impose that a variant *not seen* in the external panel
 * is tantamount to being AC=0, AN=5008 within that panel</h4>
 * <pre>
 * gatk --java-options "-Xmx4g" CalculateGenotypePosteriors \
 *   -V input.vcf.gz \
 *   -O output.vcf.gz \
 *   -supporting 1000G.phase3.integrated.sites_only.no_MATCHED_REV.hg38.vcf.gz \
 *   --num-reference-samples-if-no-call 2504
 * </pre>
 *
 * <h3>Caveat</h3>
 * <p>If applying family priors, only diploid family genotypes are supported</p>
 */
@CommandLineProgramProperties(
        summary = "This tool calculates the posterior genotype probability for each sample genotype in a VCF of input variant calls,\n" +
                " based on the genotype likelihoods from the samples themselves and, optionally, from input VCFs describing allele\n" +
                " frequencies in related populations. The input variants must possess genotype likelihoods generated by\n" +
                " HaplotypeCaller, UnifiedGenotyper or another source that provides *unbiased* genotype likelihoods.",
        oneLineSummary = "Calculate genotype posterior probabilities given family and/or known population genotypes",
        programGroup = VariantEvaluationProgramGroup.class
)
@DocumentedFeature
public final class CalculateGenotypePosteriors extends VariantWalker {

    private static final Logger logger = LogManager.getLogger(CalculateGenotypePosteriors.class);

    public static final String SUPPORTING_CALLSETS_SHORT_NAME = "supporting";
    public static final String SUPPORTING_CALLSETS_LONG_NAME = "supporting-callsets";
    public static final String NUM_REF_SAMPLES_LONG_NAME = "num-reference-samples-if-no-call";

    /**
     * Supporting external panels. Allele counts from these panels (taken from AC,AN or MLEAC,AN or raw genotypes) will
     * be used to inform the frequency distribution underlying the genotype priors. These files must be VCF 4.2 spec or later.
     */
    @Argument(fullName=SUPPORTING_CALLSETS_LONG_NAME, shortName = SUPPORTING_CALLSETS_SHORT_NAME, doc="Other callsets to use in generating genotype posteriors", optional=true)
    public List<FeatureInput<VariantContext>> supportVariants = new ArrayList<>();

    @Argument(doc="File to which variants should be written", fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME, shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME, optional = false)
    public String out = null;

    /**
     * Prior SNP pseudocounts for Dirichlet distribution of allele frequencies. The posterior distribution is a
     * Dirichlet with parameters given by pseudocounts plus the number of occurrences in the resource vcfs.
     */
    @Argument(fullName="global-prior-snp", doc="Global Dirichlet prior parameters for the SNP allele frequency",optional=true)
    public double globalPriorSnp = HomoSapiensConstants.SNP_HETEROZYGOSITY;

    /**
     * Prior indel pseudocounts for Dirichlet distribution of allele frequencies. The posterior distribution is a
     * Dirichlet with parameters given by pseudocounts plus the number of occurrences in the resource vcfs.
     */
    @Argument(fullName="global-prior-indel", doc="Global Dirichlet prior parameters for the indel allele frequency",optional=true)
    public double globalPriorIndel = HomoSapiensConstants.SNP_HETEROZYGOSITY;

    /**
     * The de novo mutation prior -- i.e. the probability that a new mutation occurs. Sensitivity analysis on known de novo
     * mutations suggests a default value of 10^-6.
     *
     */
    @Argument(fullName="de-novo-prior", doc="Prior for de novo mutations",optional=true)
    public double deNovoPrior = 1e-6;

    /**
     * When a variant is not seen in a panel, this argument controls whether to infer (and with what effective strength)
     * that only reference alleles were observed at that site. E.g. "If not seen in 1000Genomes, treat it as AC=0,
     * AN=2000", where AN=2*nSamples for human autosomes.
     */
    @Argument(fullName=NUM_REF_SAMPLES_LONG_NAME,doc="Number of hom-ref genotypes to infer at sites not present in a panel",optional=true)
    public int numRefIfMissing = 0;

    /**
     * By default the tool looks for MLEAC first, and then falls back to AC if MLEAC is not found. When this
     * flag is set, the behavior is flipped and the tool looks first for the AC field and then fall back to MLEAC or
     * raw genotypes.
     */
    @Argument(fullName="default-to-allele-count",doc="Use AC rather than MLEAC",optional=true)
    public boolean defaultToAC = false;

    /**
     * When this flag is set, only the AC and AN calculated from external sources will be used, and the calculation
     * will not use the discovered allele frequency in the callset whose posteriors are being calculated. Useful for
     * callsets containing related individuals.
     */
    @Argument(fullName="ignore-input-samples",doc="Use external information only",optional=true)
    public boolean ignoreInputSamples = false;

    /**
     * Don't add input sample ACs for variants not seen in the supporting panel.  Default is to add discovered AC from input samples
     * provided there are at least 10 input samples or if num-ref-samples-if-no-call is greater than zero.
     */
    @Argument(fullName="discovered-allele-count-priors-off",doc="Do not use discovered allele count in the input callset " +
            "for variants that do not appear in the external callset. ", optional=true)
    public boolean ignoreInputSamplesForMissingResources = false;

    /**
     * Use flat priors for indels (can be used to replicate the legacy CalculateGenotypePosteriors behavior)
     * If an input variant contains an indel allele, flat priors will be applied to that site. If a reference panel
     * variant contains an indel allele, flat priors will be applied instead.
     */
    @Argument(fullName = "use-flat-priors-for-indels", shortName = "skipIndels", doc="Use flat priors for indels")
    public boolean useFlatPriorsForIndels = false;

    /**
     * Skip application of population-based priors
     */
    @Argument(fullName="skip-population-priors",doc="Skip application of population-based priors", optional=true)
    public boolean skipPopulationPriors = false;

    /**
     * Skip application of family-based priors. Note: if pedigree file is absent, family-based priors will always be skipped.
     */
    @Argument(fullName="skip-family-priors",doc="Skip application of family-based priors", optional=true)
    public boolean skipFamilyPriors = false;

    /**
     * See https://software.broadinstitute.org/gatk/documentation/article.php?id=7696 for more details on the PED
     * format. Note that each -ped argument can be tagged with NO_FAMILY_ID, NO_PARENTS, NO_SEX, NO_PHENOTYPE to
     * tell the GATK PED parser that the corresponding fields are missing from the ped file.
     *
     */
    @Argument(fullName=StandardArgumentDefinitions.PEDIGREE_FILE_LONG_NAME, shortName=StandardArgumentDefinitions.PEDIGREE_FILE_SHORT_NAME, doc="Pedigree file for samples", optional=true)
    private File pedigreeFile = null;

    private FamilyLikelihoods famUtils;

    private VariantContextWriter vcfWriter;

    private PosteriorProbabilitiesUtils.PosteriorProbabilitiesOptions options;

    @Override
    public void onTraversalStart() {
        vcfWriter = createVCFWriter(IOUtils.getPath(out));

        SampleDB sampleDB = initializeSampleDB();

        // Get list of samples to include in the output
        final Map<String, VCFHeader> vcfHeaders = Collections.singletonMap(getDrivingVariantsFeatureInput().getName(), getHeaderForVariants());
        final Set<String> vcfSamples = VcfUtils.getSortedSampleSet(vcfHeaders, GATKVariantContextUtils.GenotypeMergeType.REQUIRE_UNIQUE);

        //Get the trios from the families passed as ped
        if (!skipFamilyPriors){
            final Set<Trio> trios = sampleDB.getTrios();
            if(trios.isEmpty()) {
                logger.warn("No PED file passed or no *non-skipped* trios found in PED file. Skipping family priors.");
                skipFamilyPriors = true;
            }
        }

        final VCFHeader header = vcfHeaders.values().iterator().next();
        if ( ! header.hasGenotypingData() ) {
            throw new UserException.BadInput("VCF has no genotypes");
        }

        if ( header.hasInfoLine(GATKVCFConstants.MLE_ALLELE_COUNT_KEY) ) {
            final VCFInfoHeaderLine mleLine = header.getInfoHeaderLine(GATKVCFConstants.MLE_ALLELE_COUNT_KEY);
            if ( mleLine.getCountType() != VCFHeaderLineCount.A ) {
                throw new UserException.BadInput("VCF does not have a properly formatted MLEAC field: the count type should be \"A\"");
            }

            if ( mleLine.getType() != VCFHeaderLineType.Integer ) {
                throw new UserException.BadInput("VCF does not have a properly formatted MLEAC field: the field type should be \"Integer\"");
            }
        }

        // Initialize VCF header
        final Set<VCFHeaderLine> headerLines = VCFUtils.smartMergeHeaders(vcfHeaders.values(), true);
        headerLines.add(VCFStandardHeaderLines.getInfoLine(VCFConstants.ALLELE_COUNT_KEY));
        headerLines.add(VCFStandardHeaderLines.getInfoLine(VCFConstants.ALLELE_FREQUENCY_KEY));
        headerLines.add(VCFStandardHeaderLines.getInfoLine(VCFConstants.ALLELE_NUMBER_KEY));
        headerLines.add(GATKVCFHeaderLines.getFormatLine(GATKVCFConstants.PHRED_SCALED_POSTERIORS_KEY));
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.GENOTYPE_PRIOR_KEY));
        if (!skipFamilyPriors) {
            headerLines.add(GATKVCFHeaderLines.getFormatLine(GATKVCFConstants.JOINT_LIKELIHOOD_TAG_NAME));
            headerLines.add(GATKVCFHeaderLines.getFormatLine(GATKVCFConstants.JOINT_POSTERIOR_TAG_NAME));
        }
        headerLines.addAll(getDefaultToolVCFHeaderLines());

        vcfWriter.writeHeader(new VCFHeader(headerLines, vcfSamples));

        final Map<String,Set<Sample>> families = sampleDB.getFamilies(vcfSamples);
        famUtils = new FamilyLikelihoods(sampleDB, deNovoPrior, vcfSamples, families);

        options = new PosteriorProbabilitiesUtils.PosteriorProbabilitiesOptions(globalPriorSnp, globalPriorIndel,
                        !ignoreInputSamples, !defaultToAC, ignoreInputSamplesForMissingResources, useFlatPriorsForIndels);
    }

    /**
     * Entry-point function to initialize the samples database from input data
     */
    private SampleDB initializeSampleDB() {
        final SampleDBBuilder sampleDBBuilder = new SampleDBBuilder(PedigreeValidationType.STRICT);
        if (pedigreeFile != null) {
            sampleDBBuilder.addSamplesFromPedigreeFiles(Collections.singletonList(pedigreeFile));
        }
        return sampleDBBuilder.getFinalSampleDB();
    }

    @Override
    public void apply(final VariantContext variant,
                      final ReadsContext readsContext,
                      final ReferenceContext referenceContext,
                      final FeatureContext featureContext) {

        final Collection<VariantContext> otherVCs = featureContext.getValues(supportVariants);

        //If no resource contains a matching variant, then add numRefIfMissing as a pseudocount to the priors
        List<VariantContext> resourcesWithMatchingStarts = otherVCs.stream()
                .filter(vc -> variant.getStart() == vc.getStart()).collect(Collectors.toList());

        //do family priors first (if applicable)
        final VariantContextBuilder builder = new VariantContextBuilder(variant);
        //only compute family priors for biallelelic sites
        if (!skipFamilyPriors && variant.isBiallelic()){
            final GenotypesContext gc = famUtils.calculatePosteriorGLs(variant);
            builder.genotypes(gc);
        }
        VariantContextUtils.calculateChromosomeCounts(builder, false);
        final VariantContext vc_familyPriors = builder.make();

        final VariantContext vc_bothPriors;
        if (!skipPopulationPriors) {
            vc_bothPriors = PosteriorProbabilitiesUtils.calculatePosteriorProbs(vc_familyPriors, resourcesWithMatchingStarts,
                    resourcesWithMatchingStarts.isEmpty() ? numRefIfMissing : 0, options);
        } else {
            vc_bothPriors = vc_familyPriors;
        }
        vcfWriter.add(vc_bothPriors);
    }

    @Override
    public void closeTool(){
        if(vcfWriter != null) {
            vcfWriter.close();
        }
    }
}

