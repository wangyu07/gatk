package org.broadinstitute.hellbender.tools.walkers;

import htsjdk.variant.variantcontext.*;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.*;
import org.broadinstitute.barclay.argparser.*;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.*;
import org.broadinstitute.hellbender.cmdline.GATKPlugin.DefaultGATKVariantAnnotationArgumentCollection;
import org.broadinstitute.hellbender.cmdline.GATKPlugin.GATKAnnotationArgumentCollection;
import org.broadinstitute.hellbender.cmdline.argumentcollections.DbsnpArgumentCollection;
import org.broadinstitute.hellbender.cmdline.programgroups.ShortVariantDiscoveryProgramGroup;
import org.broadinstitute.hellbender.engine.*;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.walkers.annotator.*;
import org.broadinstitute.hellbender.tools.walkers.genotyper.*;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.genotyper.IndexedSampleList;
import org.broadinstitute.hellbender.utils.genotyper.SampleList;
import org.broadinstitute.hellbender.utils.logging.OneShotLogger;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;
import org.broadinstitute.hellbender.utils.variant.GATKVCFHeaderLines;
import org.broadinstitute.hellbender.utils.variant.GATKVariantContextUtils;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Perform "quick and dirty" joint genotyping on one or more samples pre-called with HaplotypeCaller
 *
 * <p>
 * This tool is designed to perform joint genotyping on multiple samples pre-called with HaplotypeCaller to produce a
 * multi-sample callset in a super extra highly scalable manner. In any case, the input samples must possess genotype likelihoods produced by HaplotypeCaller
 * with `-ERC GVCF` or `-ERC BP_RESOLUTION`.
 *
 * <h3>Input</h3>
 * <p>
 * A GenomicsDB containing the samples to joint-genotype.
 * </p>
 *
 * <h3>Output</h3>
 * <p>
 * A final VCF in which all samples have been jointly genotyped.
 * </p>
 *
 * <h3>Usage example</h3>
 *
 * <h4>Perform joint genotyping on a set of GVCFs stored in a GenomicsDB</h4>
 * <pre>
 * gatk-launch --javaOptions "-Xmx4g" MakeVQSRinput \
 *   -R reference.fasta \
 *   -V gendb://genomicsdb \
 *   -O output.vcf
 * </pre>
 *
 * <h3>Caveats</h3>
 * <p><ul><li>Only GenomicsDB instances can be used as input for this tool.</li>
 * <li>To generate all the annotations necessary for VQSR, input variants must include the QUALapprox, VarDP and MQ_DP
 * annotations and GenomicsDB must have a combination operation specified for them in the vidmap.json file</li>
 * </ul></p>
 *
 * <h3>Special note on ploidy</h3>
 * <p>This tool assumes all diploid genotypes.</p>
 *
 */
@CommandLineProgramProperties(summary = "Perform \"quick and dirty\" joint genotyping on one or more samples pre-called with HaplotypeCaller",
        oneLineSummary = "Perform \"quick and dirty\" joint genotyping on one or more samples pre-called with HaplotypeCaller",
        programGroup = ShortVariantDiscoveryProgramGroup.class)
@DocumentedFeature
public final class MakeVQSRinput extends VariantWalker {
    protected final OneShotLogger warning = new OneShotLogger(this.getClass());
    public static final String ONLY_OUTPUT_CALLS_STARTING_IN_INTERVALS_FULL_NAME = "onlyOutputCallsStartingInIntervals";
    private static final String GVCF_BLOCK = "GVCFBlock";
    private final RMSMappingQuality MQcalculator = RMSMappingQuality.getInstance();

    private static final int PIPELINE_MAX_ALT_COUNT = 6;
    // cache the ploidy 2 PL array sizes for increasing numbers of alts up to the maximum of PIPELINE_MAX_ALT_COUNT
    final int[] likelihoodSizeCache = new int[PIPELINE_MAX_ALT_COUNT + 1];


    @Argument(fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME, shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            doc="File to which variants should be written", optional=false)
    private File outputFile;

    @ArgumentCollection
    private GenotypeCalculationArgumentCollection genotypeArgs = new GenotypeCalculationArgumentCollection();

    @ArgumentCollection
    private final GATKAnnotationArgumentCollection variantAnnotationArgumentCollection = new DefaultGATKVariantAnnotationArgumentCollection(
            Arrays.asList(StandardAnnotation.class.getSimpleName()),
            Collections.emptyList(),
            Collections.emptyList());

    /**
     * This option can only be activated if intervals are specified.
     */
    @Advanced
    @Argument(fullName= ONLY_OUTPUT_CALLS_STARTING_IN_INTERVALS_FULL_NAME,
            doc="Restrict variant output to sites that start within provided intervals",
            optional=true)
    private boolean onlyOutputCallsStartingInIntervals = false;

    /**
     * The rsIDs from this file are used to populate the ID column of the output.  Also, the DB INFO flag will be set
     * when appropriate. Note that dbSNP is not used in any way for the genotyping calculations themselves.
     */
    @ArgumentCollection
    private final DbsnpArgumentCollection dbsnp = new DbsnpArgumentCollection();

    // the genotyping engine
    private GenotypingEngine<?> genotypingEngine;
    // the annotation engine
    private VariantAnnotatorEngine annotationEngine;

    private ReferenceConfidenceVariantContextMerger merger;

    private VariantContextWriter vcfWriter;

    /** these are used when {@link #onlyOutputCallsStartingInIntervals) is true */
    private List<SimpleInterval> intervals;

    @Override
    public boolean requiresReference() {
        return true;
    }

    @Override
    protected boolean doGenotypeCalling() { return true; }

    @Override
    public void onTraversalStart() {
        final VCFHeader inputVCFHeader = getHeaderForVariants();

        if(onlyOutputCallsStartingInIntervals) {
            if( !hasIntervals()) {
                throw new CommandLineException.MissingArgument("-L or -XL", "Intervals are required if --" + ONLY_OUTPUT_CALLS_STARTING_IN_INTERVALS_FULL_NAME + " was specified.");
            }
        }
        intervals = hasIntervals() ? intervalArgumentCollection.getIntervals(getBestAvailableSequenceDictionary()) :
                Collections.emptyList();

        final SampleList samples = new IndexedSampleList(inputVCFHeader.getGenotypeSamples()); //todo should this be getSampleNamesInOrder?

        annotationEngine = VariantAnnotatorEngine.ofSelectedMinusExcluded(variantAnnotationArgumentCollection, dbsnp.dbsnp, Collections.emptyList());

        setupVCFWriter(inputVCFHeader, samples);

        //initialize PL size cache -- HTSJDK cache only goes up to 4 alts, but I need 6
        for(final int numAlleles : IntStream.rangeClosed(1, PIPELINE_MAX_ALT_COUNT + 1).boxed().collect(Collectors.toList())) {
            likelihoodSizeCache[numAlleles-1] = GenotypeLikelihoods.numLikelihoods(numAlleles, GATKVariantContextUtils.DEFAULT_PLOIDY);
        }
    }

    private void setupVCFWriter(VCFHeader inputVCFHeader, SampleList samples) {
        final Set<VCFHeaderLine> headerLines = new LinkedHashSet<>(inputVCFHeader.getMetaDataInInputOrder());
        headerLines.addAll(getDefaultToolVCFHeaderLines());

        // Remove GCVFBlocks
        headerLines.removeIf(vcfHeaderLine -> vcfHeaderLine.getKey().startsWith(GVCF_BLOCK));

        headerLines.addAll(annotationEngine.getVCFAnnotationDescriptions());

        // add headers for annotations added by this tool
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.MLE_ALLELE_COUNT_KEY));
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.MLE_ALLELE_FREQUENCY_KEY));
        headerLines.add(GATKVCFHeaderLines.getFormatLine(GATKVCFConstants.REFERENCE_GENOTYPE_QUALITY));
        headerLines.add(VCFStandardHeaderLines.getInfoLine(VCFConstants.DEPTH_KEY));   // needed for gVCFs without DP tags
        if ( dbsnp.dbsnp != null  ) {
            VCFStandardHeaderLines.addStandardInfoLines(headerLines, true, VCFConstants.DBSNP_KEY);
        }

        vcfWriter = createVCFWriter(outputFile);

        final Set<String> sampleNameSet = samples.asSetOfSamples();
        final VCFHeader vcfHeader = new VCFHeader(headerLines, new TreeSet<>(sampleNameSet));
        vcfWriter.writeHeader(vcfHeader);
    }

    @Override
    public void apply(VariantContext variant, ReadsContext reads, ReferenceContext ref, FeatureContext features) {
        //return early if there's no non-symbolic ALT since GDB already did the merging
        if ( !variant.isVariant() || !GenotypeGVCFs.isProperlyPolymorphic(variant) || variant.getAttributeAsInt(VCFConstants.DEPTH_KEY,0) == 0) {
            return;
        }

        //return early if variant doesn't meet QUAL threshold
        if (!variant.hasAttribute(GATKVCFConstants.RAW_QUAL_APPROX_KEY))
            warning.warn("Variant is missing the QUALapprox key -- if this tool was run with GenomicsDB input, check the vidmap.json annotation info");
        final double QUALapprox = variant.getAttributeAsDouble(GATKVCFConstants.RAW_QUAL_APPROX_KEY, 0.0);
        if(QUALapprox < genotypeArgs.STANDARD_CONFIDENCE_FOR_CALLING - 10*Math.log10(genotypeArgs.snpHeterozygosity))  //we don't apply the prior to the QUAL approx, so do it here
            return;

        //GenomicsDB merged all the annotations, but we still need to finalize MQ and QD annotations
        VariantContextBuilder builder = new VariantContextBuilder(MQcalculator.finalizeRawMQ(variant));

        final int variantDP = variant.getAttributeAsInt(GATKVCFConstants.VARIANT_DEPTH_KEY, 0);
        double QD = QUALapprox / (double)variantDP;
        builder.attribute(GATKVCFConstants.QUAL_BY_DEPTH_KEY, QD).log10PError(QUALapprox/-10.0);

        //remove the NON_REF allele and update genotypes
        if (variant.getAlleles().contains(Allele.NON_REF_ALLELE)) { //I don't know why, but sometimes GDB returns a context without a NON_REF
            final GenotypesContext calledGenotypes = removeNonRefAllele(variant);
            builder.genotypes(calledGenotypes);
            builder.alleles(variant.getAlleles().subList(0, variant.getAlleles().size() - 1));
        }

        VariantContext result = builder.make();

        SimpleInterval variantStart = new SimpleInterval(result.getContig(), result.getStart(), result.getStart());
        if (!onlyOutputCallsStartingInIntervals || intervals.stream().anyMatch(interval -> interval.contains(variantStart))) {
            vcfWriter.add(result);
            //TODO: once we're loading GTs into hail from GDB we won't need to output them here
            //vcfWriter.add(new VariantContextBuilder(combinedVC).noGenotypes().make());
        }

    }

    //assume input genotypes are diploid

    /**
     * Remove the NON_REF allele from the genotypes, updating PLs, ADs, and GT calls
     * @param vc the input variant with NON_REF
     * @return a GenotypesContext
     */
    private GenotypesContext removeNonRefAllele(final VariantContext vc) {
        final List<Allele> inputAllelesWithNonRef = vc.getAlleles();
        if(!inputAllelesWithNonRef.get(inputAllelesWithNonRef.size()-1).equals(Allele.NON_REF_ALLELE)) {
            throw new IllegalStateException("This tool assumes that the NON_REF allele is listed last, as in HaplotypeCaller GVCF output,"
            + " but that was not the case at position " + vc.getContig() + ":" + vc.getStart() + ".");
        }
        final GenotypesContext mergedGenotypes = GenotypesContext.create();

        final int maximumAlleleCount = inputAllelesWithNonRef.size();
        final int newPLsize = maximumAlleleCount <= PIPELINE_MAX_ALT_COUNT? likelihoodSizeCache[maximumAlleleCount-1] :
                GenotypeLikelihoods.numLikelihoods(maximumAlleleCount, GATKVariantContextUtils.DEFAULT_PLOIDY);
        final List<Allele> targetAlleles = inputAllelesWithNonRef.subList(0, maximumAlleleCount-1); //remove NON_REF

        for ( final Genotype g : vc.getGenotypes() ) {
            final String name = g.getSampleName();
            if(g.getPloidy() != GATKVariantContextUtils.DEFAULT_PLOIDY) {
                throw new UserException.BadInput("This tool assumes diploid genotypes, but sample " + name + " has ploidy "
                        + g.getPloidy() + " at position " + vc.getContig() + ":" + vc.getStart() + ".");
            }
            final GenotypeBuilder genotypeBuilder = new GenotypeBuilder(g);
            genotypeBuilder.name(name);
            if (g.hasPL()) {
                final int[] PLs = trimPLs(g, newPLsize);
                final int[] AD = g.hasAD() ? trimADs(g, targetAlleles.size()) : null;
                genotypeBuilder.PL(PLs).AD(AD);
                GATKVariantContextUtils.makeGenotypeCall(GATKVariantContextUtils.DEFAULT_PLOIDY, genotypeBuilder, GenotypeAssignmentMethod.USE_PLS_TO_ASSIGN, GenotypeLikelihoods.fromPLs(PLs).getAsVector(), targetAlleles);
            }
            mergedGenotypes.add(genotypeBuilder.make());
        }

        return mergedGenotypes;
    }

    static int[] trimPLs(final Genotype g, final int newPLsize) {
        final int[] oldPLs = g.getPL();
        final int[] newPLs = new int[newPLsize];
        System.arraycopy(oldPLs, 0, newPLs, 0, newPLsize);
        return newPLs;
    }

    static int[] trimADs(final Genotype g, final int newAlleleNumber) {
        final int[] oldADs = g.getAD();
        final int[] newADs = new int[newAlleleNumber];
        System.arraycopy(oldADs, 0, newADs, 0, newAlleleNumber);
        return newADs;
    }

    @Override
    public void closeTool() {
        if ( vcfWriter != null) {
            vcfWriter.close();
        }
    }
}