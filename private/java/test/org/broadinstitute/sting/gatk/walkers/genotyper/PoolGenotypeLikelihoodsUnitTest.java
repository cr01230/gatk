package org.broadinstitute.sting.gatk.walkers.genotyper;

import net.sf.samtools.SAMFileHeader;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.GenomeLocParser;
import org.broadinstitute.sting.utils.MathUtils;
import org.broadinstitute.sting.utils.pileup.PileupElement;
import org.broadinstitute.sting.utils.pileup.ReadBackedPileup;
import org.broadinstitute.sting.utils.pileup.ReadBackedPileupImpl;
import org.broadinstitute.sting.utils.sam.ArtificialSAMUtils;
import org.broadinstitute.sting.utils.variantcontext.Allele;
import org.broadinstitute.sting.utils.variantcontext.GenotypeLikelihoods;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by IntelliJ IDEA.
 * User: delangel
 * Date: 4/3/12
 * Time: 7:26 PM
 * To change this template use File | Settings | File Templates.
 */
public class PoolGenotypeLikelihoodsUnitTest {


    @Test
    public void testStoringLikelihoodElements() {


        // basic test storing a given PL vector in a PoolGenotypeLikelihoods object and then retrieving it back

        int ploidy = 20;
        int numAlleles = 4;
        int res = GenotypeLikelihoods.calculateNumLikelihoods(numAlleles, ploidy);
 //       System.out.format("Alt Alleles: %d, Ploidy: %d, #Likelihoods: %d\n", numAltAlleles, ploidy, res);

        List<Allele> alleles = new ArrayList<Allele>();
        alleles.add(Allele.create("T",true));
        alleles.add(Allele.create("C",false));
        alleles.add(Allele.create("A",false));
        alleles.add(Allele.create("G",false));

        double[] gls = new double[res];

        for (int k=0; k < gls.length; k++)
            gls[k]= (double)k;

        PoolGenotypeLikelihoods gl = new PoolSNPGenotypeLikelihoods(alleles, gls,ploidy, null, true);
        double[] glnew = gl.getLikelihoods();

        Assert.assertEquals(gls, glnew);
    }

    @Test
    public void testElementStorageCache() {
        // compare cached element storage with compuationally hard-coded iterative computation

        for (int ploidy = 2; ploidy < 10; ploidy++) {
            for (int nAlleles = 2; nAlleles < 10; nAlleles++)
                Assert.assertEquals(PoolGenotypeLikelihoods.getNumLikelihoodElements(nAlleles,ploidy),
                        GenotypeLikelihoods.calculateNumLikelihoods(nAlleles,ploidy));
        }

    }
    
    @Test
    public void testVectorToLinearIndex() {
        
        // create iterator, compare linear index given by iterator with closed form function
        int numAlleles = 4;
        int ploidy = 2;
        PoolGenotypeLikelihoods.SumIterator iterator = new PoolGenotypeLikelihoods.SumIterator(numAlleles, ploidy);

        while(iterator.hasNext()) {
            System.out.format("\n%d:",iterator.getLinearIndex());
            int[] a =  iterator.getCurrentVector();
            for (int i=0; i < a.length; i++)
                System.out.format("%d ",a[i]);

            
            int computedIdx = PoolGenotypeLikelihoods.getLinearIndex(a, numAlleles, ploidy);
            System.out.format("Computed idx = %d\n",computedIdx);
            iterator.next();
        }

    }
    @Test
    public void testSubsetToAlleles() {
        
        int ploidy = 2;
        int numAlleles = 4;
        int res = GenotypeLikelihoods.calculateNumLikelihoods(numAlleles, ploidy);
        //       System.out.format("Alt Alleles: %d, Ploidy: %d, #Likelihoods: %d\n", numAltAlleles, ploidy, res);

        List<Allele> originalAlleles = new ArrayList<Allele>();
        originalAlleles.add(Allele.create("T",true));
        originalAlleles.add(Allele.create("C",false));
        originalAlleles.add(Allele.create("A",false));
        originalAlleles.add(Allele.create("G",false));

        double[] oldLikelihoods = new double[res];

        for (int k=0; k < oldLikelihoods.length; k++)
            oldLikelihoods[k]= (double)k;

        List<Allele> allelesToSubset = new ArrayList<Allele>();
        allelesToSubset.add(Allele.create("A",false));
        allelesToSubset.add(Allele.create("C",false));
        
        double[] newGLs = PoolGenotypeLikelihoods.subsetToAlleles(oldLikelihoods, ploidy,
        originalAlleles, allelesToSubset);


        /*
            For P=2, N=4, default iteration order:
                0:2 0 0 0
                1:1 1 0 0
                2:0 2 0 0
                3:1 0 1 0
                4:0 1 1 0
                5:0 0 2 0
                6:1 0 0 1
                7:0 1 0 1
                8:0 0 1 1
                9:0 0 0 2

            For P=2,N=2, iteration order is:
                0:2 0
                1:1 1
                2:0 2

            From first list, if we're extracting alleles 2 and 1, we need all elements that have zero at positions 0 and 3.
            These are only elements {2,4,5}. Since test is flipping alleles 2 and 1, order is reversed.
  */
        Assert.assertEquals(newGLs,new double[]{5.0,4.0,2.0});
    }
    @Test
    public void testIndexIterator() {
        int[] seed = new int[]{1,2,3,4};
        PoolGenotypeLikelihoods.SumIterator iterator = runIterator(seed,-1);
        // Assert.assertTrue(compareIntArrays(iterator.getCurrentVector(), seed));
        Assert.assertEquals(iterator.getLinearIndex(),prod(seed)-1);

        seed = new int[]{1,0,1,1};
        iterator = runIterator(seed,-1);
        //  Assert.assertTrue(compareIntArrays(iterator.getCurrentVector(), seed));
        Assert.assertEquals(iterator.getLinearIndex(),prod(seed)-1);

        seed = new int[]{5};
        iterator = runIterator(seed,-1);
        //  Assert.assertTrue(compareIntArrays(iterator.getCurrentVector(), seed));
        Assert.assertEquals(iterator.getLinearIndex(),prod(seed)-1);

        // Diploid, # alleles = 4
        seed = new int[]{2,2,2,2};
        iterator = runIterator(seed,2);
        //  Assert.assertTrue(compareIntArrays(iterator.getCurrentVector(), seed));
        Assert.assertEquals(iterator.getLinearIndex(),9);

        // Diploid, # alleles = 2
        seed = new int[]{2,2};
        iterator = runIterator(seed,2);
        //  Assert.assertTrue(compareIntArrays(iterator.getCurrentVector(), seed));
        Assert.assertEquals(iterator.getLinearIndex(),2);

        // Diploid, # alleles = 3
        seed = new int[]{2,2,2};
        iterator = runIterator(seed,2);
        //  Assert.assertTrue(compareIntArrays(iterator.getCurrentVector(), seed));
        Assert.assertEquals(iterator.getLinearIndex(),5);

        // Triploid, # alleles = 2
        seed = new int[]{3,3};
        iterator = runIterator(seed,3);
        //  Assert.assertTrue(compareIntArrays(iterator.getCurrentVector(), seed));
        Assert.assertEquals(iterator.getLinearIndex(),3);
        // Triploid, # alleles = 3
        seed = new int[]{3,3,3};
        iterator = runIterator(seed,3);
        //  Assert.assertTrue(compareIntArrays(iterator.getCurrentVector(), seed));
        Assert.assertEquals(iterator.getLinearIndex(),9);

        // Triploid, # alleles = 4
        seed = new int[]{3,3,3,3};
        iterator = runIterator(seed,3);
        //  Assert.assertTrue(compareIntArrays(iterator.getCurrentVector(), seed));
        Assert.assertEquals(iterator.getLinearIndex(),19);

        // 8-ploid, # alleles = 6
        seed = new int[]{8,8,8,8,8,8};
        iterator = runIterator(seed,8);
        //  Assert.assertTrue(compareIntArrays(iterator.getCurrentVector(), seed));
        Assert.assertEquals(iterator.getLinearIndex(),1286);


    }

    private PoolGenotypeLikelihoods.SumIterator runIterator(int[] seed, int restrictSumTo) {
        PoolGenotypeLikelihoods.SumIterator iterator = new PoolGenotypeLikelihoods.SumIterator(seed, restrictSumTo);

        while(iterator.hasNext()) {
             System.out.format("\n%d:",iterator.getLinearIndex());
            int[] a =  iterator.getCurrentVector();
            for (int i=0; i < seed.length; i++)
                System.out.format("%d ",a[i]);

            iterator.next();
        }

        return iterator;

    }

    private static int prod(int[] x) {
        int prod = 1;
        for (int k=0; k < x.length; k++) {
            prod *= (1+x[k]);
        }
        return prod;
    }

    @Test
    public void testErrorModel() {
        final ArtificialReadPileupTestProvider refPileupTestProvider = new ArtificialReadPileupTestProvider(1,"ref");
        final byte minQ = 5;
        final byte maxQ = 40;
        final byte refByte = refPileupTestProvider.getRefByte();
        final byte altByte = refByte == (byte)'T'? (byte) 'C': (byte)'T';
        final String refSampleName = refPileupTestProvider.getSampleNames().get(0);
        final List<Allele> trueAlleles = new ArrayList<Allele>();
        trueAlleles.add(Allele.create(refByte, true));

        final int[] matchArray = {95, 995, 9995, 10000};
        final int[] mismatchArray = {1,5,10,20};

        for (int matches: matchArray) {
            for (int mismatches: mismatchArray) {
                // get artificial alignment context for ref sample
                Map<String,AlignmentContext> refContext = refPileupTestProvider.getAlignmentContextFromAlleles(0, new String(new byte[]{altByte}), new int[]{matches, mismatches});
                ReadBackedPileup refPileup = refContext.get(refSampleName).getBasePileup();
                ErrorModel emodel = new ErrorModel(minQ,maxQ, (byte)20, refPileup, trueAlleles, 0.0);
                final double[] errorVec = emodel.getErrorModelVector().getProbabilityVector();

                final double mlEst = -10.0*Math.log10((double)mismatches/(double)(matches+mismatches));
                final int peakIdx = (int)Math.round(mlEst);
                System.out.format("Matches:%d Mismatches:%d peakIdx:%d\n",matches, mismatches, peakIdx);
                Assert.assertEquals(MathUtils.maxElementIndex(errorVec),peakIdx);

            }
        }


    }
    @Test
    public void testAddPileupToPoolGL() {
        int minQ = 30;
        int maxQ = 40;
        int numSamples = 2;

        // have a high quality site say Q40 site, and create artificial pileups for one single sample, at coverage N, with given
        // true pool AC = x.

        ArtificialReadPileupTestProvider refPileupTestProvider = new ArtificialReadPileupTestProvider(1,"ref");

//        Map<String,AlignmentContext> refContext = refPileupTestProvider.getAlignmentContextFromAlleles(0, "T", new int[]{matches, mismatches});
/*        ReferenceSample refsample = new ReferenceSample("ref",pileup, trueAlleles);
        ErrorModel emodel = new ErrorModel(minQ,maxQ,(byte)20,refSample,0.0);
  */  }
    
    

}
