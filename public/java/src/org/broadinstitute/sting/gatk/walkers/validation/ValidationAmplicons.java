package org.broadinstitute.sting.gatk.walkers.validation;

import net.sf.picard.reference.ReferenceSequenceFileFactory;
import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMSequenceDictionary;
import org.broadinstitute.sting.alignment.Alignment;
import org.broadinstitute.sting.alignment.bwa.BWAConfiguration;
import org.broadinstitute.sting.alignment.bwa.BWTFiles;
import org.broadinstitute.sting.alignment.bwa.c.BWACAligner;
import org.broadinstitute.sting.commandline.Argument;
import org.broadinstitute.sting.commandline.Output;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.refdata.features.table.TableFeature;
import org.broadinstitute.sting.gatk.walkers.DataSource;
import org.broadinstitute.sting.gatk.walkers.RMD;
import org.broadinstitute.sting.gatk.walkers.Requires;
import org.broadinstitute.sting.gatk.walkers.RodWalker;
import org.broadinstitute.sting.utils.BaseUtils;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.variantcontext.VariantContext;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: chartl
 * Date: 6/13/11
 * Time: 2:12 PM
 * To change this template use File | Settings | File Templates.
 */
@Requires(value={DataSource.REFERENCE}, referenceMetaData={@RMD(name="ProbeIntervals",type=TableFeature.class),
@RMD(name="ValidateAlleles",type=VariantContext.class),@RMD(name="MaskAlleles",type=VariantContext.class)})
public class ValidationAmplicons extends RodWalker<Integer,Integer> {

    @Argument(doc="Lower case SNPs rather than replacing with 'N'",fullName="lowerCaseSNPs",required=false)
    boolean lowerCaseSNPs = false;

    @Argument(doc="Size of the virtual primer to use for lower-casing regions with low specificity",fullName="virtualPrimerSize",required=false)
    int virtualPrimerSize = 20;

    @Argument(doc="Monomorphic sites in the mask file will be treated as filtered",fullName="filterMonomorphic",required=false)
    boolean filterMonomorphic = false;

    @Argument(doc="Do not use BWA, lower-case repeats only",fullName="doNotUseBWA",required=false)
    boolean doNotUseBWA = false;

    GenomeLoc prevInterval;
    GenomeLoc allelePos;
    String probeName;
    StringBuilder sequence;
    StringBuilder rawSequence;
    boolean sequenceInvalid;
    List<String> invReason;
    int indelCounter;

    @Argument(fullName="target_reference",shortName="target_ref",doc="The reference to which reads in the source file should be aligned.  Alongside this reference should sit index files " +
                                                                     "generated by bwa index -d bwtsw.  If unspecified, will default " +
                                                                     "to the reference specified via the -R argument.",required=false)
    private File targetReferenceFile = null;

    @Output
    PrintStream out;

    BWACAligner aligner = null;

    private SAMFileHeader header = null;

    public void initialize() {
        if ( ! doNotUseBWA ) {
            if(targetReferenceFile == null)
                targetReferenceFile = getToolkit().getArguments().referenceFile;
            BWTFiles bwtFiles = new BWTFiles(targetReferenceFile.getAbsolutePath());
            BWAConfiguration configuration = new BWAConfiguration();
            aligner = new BWACAligner(bwtFiles,configuration);
            header = new SAMFileHeader();
            SAMSequenceDictionary referenceDictionary =
                    ReferenceSequenceFileFactory.getReferenceSequenceFile(targetReferenceFile).getSequenceDictionary();
            header.setSequenceDictionary(referenceDictionary);
            header.setSortOrder(SAMFileHeader.SortOrder.unsorted);
        }
    }

    public Integer reduceInit() {
        prevInterval = null;
        sequence = null;
        rawSequence = null;
        sequenceInvalid = false;
        probeName = null;
        invReason = null;
        indelCounter = 0;
        return 0;
    }

    public Integer map(RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context) {
        if ( tracker == null || ! tracker.hasROD("ProbeIntervals")) { return null; }

        GenomeLoc interval = ((TableFeature) tracker.getReferenceMetaData("ProbeIntervals",true).get(0)).getLocation();
        //logger.debug(interval);
        if ( prevInterval == null || ! interval.equals(prevInterval) ) {
            // we're in a new interval, we should:
            // 1) print out previous data
            // 2) reset internal data
            // 3) instantiate traversal of this interval

            // step 1:
            if ( prevInterval != null ) {
                // there was a previous interval
                validateSequence(); // ensure the sequence in the region is valid
                // next line removed in favor of the one after
                if ( doNotUseBWA ) {
                    lowerRepeats(); // change repeats in sequence to lower case
                } else {
                    lowerNonUniqueSegments();
                }
                print(); // print out the fasta sequence
            }

            // step 2:
            prevInterval = interval;
            allelePos = null;
            sequence = new StringBuilder();
            rawSequence = new StringBuilder();
            sequenceInvalid = false;
            invReason = new LinkedList<String>();
            logger.debug(Utils.join("\t",((TableFeature) tracker.getReferenceMetaData("ProbeIntervals",true).get(0)).getAllValues()));
            probeName = ((TableFeature) tracker.getReferenceMetaData("ProbeIntervals",true).get(0)).getValue(1);
            indelCounter = 0;
        }

        // step 3 (or 1 if not new):
        // build up the sequence

        VariantContext mask = tracker.getVariantContext(ref,"MaskAlleles",ref.getLocus());
        VariantContext validate = tracker.getVariantContext(ref,"ValidateAlleles",ref.getLocus());

        if ( mask == null && validate == null ) {
            if ( indelCounter > 0 ) {
                sequence.append('N');
                indelCounter--;
            } else {
                sequence.append(Character.toUpperCase((char) ref.getBase()));
            }
            rawSequence.append(Character.toUpperCase((char) ref.getBase()));
        } else if ( validate != null ) {
            // doesn't matter if there's a mask here too -- this is what we want to validate
            if ( validate.isFiltered() ) {
                logger.warn("You are attempting to validate a filtered site. Why are you attempting to validate a filtered site? You should not be attempting to validate a filtered site.");
                sequenceInvalid = true;
                invReason.add("SITE_IS_FILTERED");
            }
            if ( validate.isIndel() ) {
                sequence.append(Character.toUpperCase((char)ref.getBase()));
                rawSequence.append(Character.toUpperCase((char)ref.getBase()));
            }
            sequence.append('[');
            sequence.append(validate.getAlternateAllele(0).toString());
            sequence.append('/');
            sequence.append(validate.getReference().toString());
            sequence.append(']');
            // do this to the raw sequence to -- the indeces will line up that way
            rawSequence.append('[');
            rawSequence.append(validate.getAlternateAllele(0).getBaseString());
            rawSequence.append('/');
            rawSequence.append(validate.getReference().getBaseString());
            rawSequence.append(']');
            allelePos = ref.getLocus();
            if ( indelCounter > 0 ) {
                logger.warn("An indel event overlaps the event to be validated. This completely invalidates the probe.");
                sequenceInvalid = true;
                invReason.add("INDEL_OVERLAPS_VALIDATION_SITE");
                if ( validate.isSNP() ) {
                    indelCounter--;
                } else {
                    indelCounter -= validate.getEnd()-validate.getStart();
                }
            }
        } else /* (mask != null && validate == null ) */ {
            if ( ! mask.isSNP() && ! mask.isFiltered() && ( ! filterMonomorphic || ! mask.isMonomorphic() )) {
                logger.warn("Mask Variant Context on the following warning line is not a SNP. Currently we can only mask out SNPs. This probe will not be designed.");
                logger.warn(String.format("%s:%d-%d\t%s\t%s",mask.getChr(),mask.getStart(),mask.getEnd(),mask.isInsertion() ? "INS" : "DEL", Utils.join(",",mask.getAlleles())));
                sequenceInvalid = true;
                invReason.add(mask.isInsertion() ? "INSERTION" : "DELETION");
                // note: indelCounter could be > 0 (could have small deletion within larger one). This always selects
                // the larger event.
                int indelCounterNew = mask.isInsertion() ? 2 : mask.getEnd()-mask.getStart();
                if ( indelCounterNew > indelCounter ) {
                    indelCounter = indelCounterNew;
                }
                //sequence.append((char) ref.getBase());
                //sequence.append(mask.isInsertion() ? 'I' : 'D');
                sequence.append("N");
                indelCounter--;
                rawSequence.append(Character.toUpperCase((char) ref.getBase()));
            } else if ( indelCounter > 0 ) {
                // previous section resets the indel counter. Doesn't matter if there's a SNP underlying this, we just want to append an 'N' and move on.
                sequence.append('N');
                indelCounter--;
                rawSequence.append(Character.toUpperCase((char)ref.getBase()));
            } else if ( ! mask.isFiltered() && ( ! filterMonomorphic || ! mask.isMonomorphic() )){
                logger.debug("SNP in mask found at " + ref.getLocus().toString());

                if ( lowerCaseSNPs ) {
                    sequence.append(Character.toLowerCase((char) ref.getBase()));
                } else {
                    sequence.append((char) BaseUtils.N);
                }

                rawSequence.append(Character.toUpperCase((char) ref.getBase()));
            } else if ( mask.isSNP() ) {
                logger.debug("SNP in mask found at "+ref.getLocus().toString()+" but was either filtered or monomorphic");
                sequence.append((Character.toUpperCase((char) ref.getBase())));
                rawSequence.append(Character.toUpperCase((char) ref.getBase()));
            }
        }

        return 1;
    }

    public Integer reduce(Integer i, Integer j) {
        return 0;
    }

    public void onTraversalDone(Integer fin ) {
        validateSequence();
        if ( doNotUseBWA ) {
            lowerRepeats();
        } else {
            lowerNonUniqueSegments();
            aligner.close();
        }
        print();
    }

    public void validateSequence() {
        // code for ensuring primer sequence is valid goes here

        // validate that there are no masked sites near to the variant site
        String seq = sequence.toString();
        int start = seq.indexOf('[') - 4;
        int end = seq.indexOf(']') + 5;

        if ( start < 50 ) {
            logger.warn("There is not enough sequence before the start position of the probed allele for adequate probe design. This site will not be designed.");
            sequenceInvalid = true;
            invReason.add("START_TOO_CLOSE");
        } else if ( end > seq.length() - 50 ) {
            logger.warn("There is not enough sequence after the end position of the probed allele fore adequate probe design. This site will not be desinged. ");
            sequenceInvalid = true;
            invReason.add("END_TOO_CLOSE");
        } else {
            boolean maskNearVariantSite = false;
            for ( int i = start; i < end; i++ ) {
                maskNearVariantSite |= (seq.charAt(i) == 'N' || Character.isLowerCase(seq.charAt(i)));
            }

            if ( maskNearVariantSite ) {
                logger.warn("There is one (or more) mask variants within 4 basepair of the variant given for validation. This site will not be designed.");
                sequenceInvalid = true;
                invReason.add("VARIANT_TOO_NEAR_PROBE");
            }
        }

        if ( seq.indexOf("[") != seq.lastIndexOf("[") ) {
            logger.warn("Multiple probe variants were found within this interval. Please fix the definitions of the intervals so they do not overlap.");
            sequenceInvalid = true;
            invReason.add("MULTIPLE_PROBES");
        }

        if ( seq.indexOf("[") < 0 ) {
            logger.warn("No variants in region were found. This site will not be designed.");
            sequenceInvalid = true;
            invReason.add("NO_VARIANTS_FOUND");
        }
    }

    public void lowerNonUniqueSegments() {
        if ( ! invReason.contains("MULTIPLE_PROBES") && !invReason.contains("NO_VARIANTS_FOUND") ) {
            String leftFlank = rawSequence.toString().split("\\[")[0];
            String rightFlank = rawSequence.toString().split("\\]")[1];
            List<Integer> badLeft = getBadIndeces(leftFlank);
            List<Integer> badRight = getBadIndeces(rightFlank);
            // propagate lowercases into the printed sequence
            for ( int idx = 0; idx < leftFlank.length(); idx++ ) {
                while ( badLeft.size() > 0 && idx > badLeft.get(0) + virtualPrimerSize ) {
                    badLeft.remove(0);
                }

                if ( badLeft.size() > 0 && badLeft.get(0) <= idx && idx <= badLeft.get(0) + virtualPrimerSize ) {
                    sequence.setCharAt(idx,Character.toLowerCase(sequence.charAt(idx)));
                }
            }

            int offset = 1 + rawSequence.indexOf("]");
            for ( int i= 0; i < rightFlank.length(); i++ ) {
                int idx = i + offset;
                while ( badRight.size() > 0 && i > badRight.get(0) + virtualPrimerSize ) {
                    //logger.debug("Removing "+badRight.get(0)+" because "+(badRight.get(0)+virtualPrimerSize)+" < "+i);
                    badRight.remove(0);
                }

                if ( badRight.size() > 0 && badRight.get(0) <= i && i <= badRight.get(0) + virtualPrimerSize ) {
                    //logger.debug("Resetting character on right flank: "+idx+" "+i+" offset="+offset);
                    //logger.debug(sequence);
                    sequence.setCharAt(idx,Character.toLowerCase(sequence.charAt(idx)));
                    //logger.debug(sequence);
                }
            }
        }
    }

    private List<Integer> getBadIndeces(String sequence) {

        List<Integer> badLeftIndeces = new ArrayList<Integer>(sequence.length()-virtualPrimerSize);
            for ( int i = 0; i < sequence.length()-virtualPrimerSize ; i++ ) {
                String toAlign = sequence.substring(i,i+virtualPrimerSize);
                Iterable<Alignment[]> allAlignments = aligner.getAllAlignments(toAlign.getBytes());
                for ( Alignment[] alignments : allAlignments ) {
                    if ( alignments.length > 1 ) {
                        if ( alignments[0].getMappingQuality() == 0 ) {
                            // this region is bad -- multiple MQ alignments
                            badLeftIndeces.add(i);
                        }
                    }
                }
            }

        return badLeftIndeces;
    }


    /**
     * Note- this is an old function - a proxy for identifying regions with low specificity to genome. Saved in case the alignment-based version
     * turns out to be worse than just doing a simple repeat-lowering method.
     */
    public void lowerRepeats() {
        // convert to lower case low-complexity repeats, e.g. tandem k-mers
        final int K_LIM = 8;
        String seq = sequence.toString();
        StringBuilder newSequence = new StringBuilder();
        int start_pos = 0;
        while( start_pos < seq.length() ) {
            boolean broke = false;
            for ( int length = K_LIM; length > 1; length -- ) {
                //logger.debug(String.format("start1: %d end1: %d start2: %d end2: %d str: %d",start_pos,start_pos+length,start_pos+length,start_pos+2*length,seq.length()));
                if ( start_pos + 2*length> seq.length() ) {
                    continue;
                }
                if ( equalsIgnoreNs(seq.substring(start_pos,start_pos+length),seq.substring(start_pos+length,start_pos+2*length)) ) {
                    newSequence.append(seq.substring(start_pos,start_pos+length).toLowerCase());
                    newSequence.append(seq.substring(start_pos+length,start_pos+2*length).toLowerCase());
                    start_pos += 2*length;
                    broke = true;
                    break;
                }
            }

            if ( ! broke ) {
                newSequence.append(seq.substring(start_pos,start_pos+1));
                start_pos++;
            }

        }

        if ( seq.indexOf("[") != seq.lastIndexOf("[") ) {
            return;
        }

        sequence = newSequence;
    }

    public boolean equalsIgnoreNs(String one, String two) {
        if ( one.length() != two.length() ) { return false; }
        for ( int idx = 0; idx < one.length(); idx++ ) {
            if ( Character.toUpperCase(one.charAt(idx)) != Character.toUpperCase(two.charAt(idx)) ) {
                if ( Character.toUpperCase(one.charAt(idx)) != 'N' && Character.toUpperCase(two.charAt(idx)) != 'N' ) {
                    return false;
                }
            }
        }

        //logger.debug(String.format("one: %s two: %s",one,two));

        return true;
    }

    public void print() {
        String valid;
        if ( sequenceInvalid ) {
            valid = "";
            while ( invReason.size() > 0 ) {
                String reason = invReason.get(0);
                invReason.remove(reason);
                int num = 1;
                while ( invReason.contains(reason) ) {
                    num++;
                    invReason.remove(reason);
                }
                valid += String.format("%s=%d,",reason,num);
            }
        } else {
            valid = "Valid";
        }

        String seqIdentity = sequence.toString().replace('n', 'N').replace('i', 'I').replace('d', 'D');
        out.printf(">%s %s %s%n%s%n", allelePos != null ? allelePos.toString() : "multiple", valid, probeName, seqIdentity);
    }
}
