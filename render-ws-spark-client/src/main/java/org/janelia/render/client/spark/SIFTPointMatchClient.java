package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureMatchResult;
import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.MatchDataClientParameters;
import org.janelia.render.client.spark.cache.CanvasDataCache;
import org.janelia.render.client.spark.cache.CanvasFeatureListLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for generating and storing SIFT point matches for a specified set of canvas (e.g. tile) pairs.
 *
 * @author Eric Trautman
 */
public class SIFTPointMatchClient
        implements Serializable {

    @SuppressWarnings("ALL")
    public static class Parameters extends MatchDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --collection parameters defined in MatchDataClientParameters

        @Parameter(names = "--pairJson", description = "JSON file where tile pairs are stored (.json, .gz, or .zip)", required = true)
        private List<String> pairJson;

        @Parameter(
                names = "--renderWithFilter",
                description = "Render tiles using a filter for intensity correction",
                required = false,
                arity = 1)
        private boolean renderWithFilter = true;

        @Parameter(
                names = "--renderWithoutMask",
                description = "Render tiles without a mask",
                required = false,
                arity = 1)
        private boolean renderWithoutMask = true;

        @Parameter(names = "--renderFullScaleWidth", description = "Full scale width for all rendered tiles", required = false)
        private Integer renderFullScaleWidth;

        @Parameter(names = "--renderFullScaleHeight", description = "Full scale height for all rendered tiles", required = false)
        private Integer renderFullScaleHeight;

        @Parameter(names = "--renderScale", description = "Render tiles at this scale", required = false)
        private Double renderScale = 1.0;

        @Parameter(
                names = "--fillWithNoise",
                description = "Fill each canvas image with noise before rendering to improve point match derivation",
                required = false,
                arity = 1)
        private boolean fillWithNoise = true;

        @Parameter(names = "--SIFTfdSize", description = "SIFT feature descriptor size: how many samples per row and column", required = false)
        private Integer fdSize = 8;

        @Parameter(names = "--SIFTminScale", description = "SIFT minimum scale: minSize * minScale < size < maxSize * maxScale", required = false)
        private Double minScale = 0.5;

        @Parameter(names = "--SIFTmaxScale", description = "SIFT maximum scale: minSize * minScale < size < maxSize * maxScale", required = false)
        private Double maxScale = 0.85;

        @Parameter(names = "--SIFTsteps", description = "SIFT steps per scale octave", required = false)
        private Integer steps = 3;

        @Parameter(names = "--matchRod", description = "Ratio of distances for matches", required = false)
        private Float matchRod = 0.92f;

        @Parameter(names = "--matchModelType", description = "Type of model for match filtering", required = false)
        private ModelType matchModelType = ModelType.AFFINE;

        @Parameter(names = "--matchIterations", description = "Match filter iterations", required = false)
        private Integer matchIterations = 1000;

        @Parameter(names = "--matchMaxEpsilon", description = "Minimal allowed transfer error for match filtering", required = false)
        private Float matchMaxEpsilon = 20.0f;

        @Parameter(names = "--matchMinInlierRatio", description = "Minimal ratio of inliers to candidates for match filtering", required = false)
        private Float matchMinInlierRatio = 0.0f;

        @Parameter(names = "--matchMinNumInliers", description = "Minimal absolute number of inliers for match filtering", required = false)
        private Integer matchMinNumInliers = 4;

        @Parameter(names = "--matchMaxTrust", description = "Reject match candidates with a cost larger than maxTrust * median cost", required = false)
        private Double matchMaxTrust = 3.0;

        @Parameter(names = "--matchMaxNumInliers", description = "Maximum number of inliers for match filtering", required = false)
        private Integer matchMaxNumInliers;

        @Parameter(names = "--maxFeatureCacheGb", description = "Maximum number of gigabytes of features to cache", required = false)
        private Integer maxFeatureCacheGb = 2;

        @Parameter(names = "--clipHeight", description = "Number of full scale pixels to include in rendered clips of TOP/BOTTOM oriented montage tiles", required = false)
        private Integer clipHeight;

        @Parameter(names = "--clipWidth", description = "Number of full scale pixels to include in rendered clips of LEFT/RIGHT oriented montage tiles", required = false)
        private Integer clipWidth;

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, SIFTPointMatchClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final SIFTPointMatchClient client = new SIFTPointMatchClient(parameters);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    public SIFTPointMatchClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
    }

    public void run() throws IOException, URISyntaxException {

        final SparkConf conf = new SparkConf().setAppName("SIFTPointMatchClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);

        for (final String pairJsonFileName : parameters.pairJson) {
            generateMatchesForPairFile(sparkContext, pairJsonFileName);
        }

        sparkContext.stop();
    }

    private void generateMatchesForPairFile(final JavaSparkContext sparkContext,
                                            final String pairJsonFileName)
            throws IOException, URISyntaxException {

        LOG.info("generateMatchesForPairFile: pairJsonFileName is {}", pairJsonFileName);

        final RenderableCanvasIdPairs renderableCanvasIdPairs =
                RenderableCanvasIdPairsUtilities.load(pairJsonFileName);

        final String renderParametersUrlTemplateForRun =
                RenderableCanvasIdPairsUtilities.getRenderParametersUrlTemplateForRun(
                        renderableCanvasIdPairs,
                        parameters.baseDataUrl,
                        parameters.renderFullScaleWidth,
                        parameters.renderFullScaleHeight,
                        parameters.renderScale,
                        parameters.renderWithFilter,
                        parameters.renderWithoutMask);

        final long cacheMaxKilobytes = parameters.maxFeatureCacheGb * 1000000;
        final CanvasFeatureListLoader featureLoader =
                new CanvasFeatureListLoader(
                        renderParametersUrlTemplateForRun,
                        getCanvasFeatureExtractor());

        featureLoader.setClipInfo(parameters.clipWidth, parameters.clipHeight);

        final double renderScale = parameters.renderScale;

        // broadcast to all nodes
        final Broadcast<Long> broadcastCacheMaxKilobytes = sparkContext.broadcast(cacheMaxKilobytes);
        final Broadcast<CanvasFeatureListLoader> broadcastFeatureLoader = sparkContext.broadcast(featureLoader);
        final Broadcast<CanvasFeatureMatcher> broadcastFeatureMatcher =
                sparkContext.broadcast(getCanvasFeatureMatcher());

        final JavaRDD<OrderedCanvasIdPair> rddCanvasIdPairs =
                sparkContext.parallelize(renderableCanvasIdPairs.getNeighborPairs());

        final JavaRDD<CanvasMatches> rddMatches = rddCanvasIdPairs.mapPartitionsWithIndex(
                new Function2<Integer, Iterator<OrderedCanvasIdPair>, Iterator<CanvasMatches>>() {

                    @Override
                    public Iterator<CanvasMatches> call(final Integer partitionIndex,
                                                        final Iterator<OrderedCanvasIdPair> pairIterator)
                            throws Exception {

                        LogUtilities.setupExecutorLog4j("partition " + partitionIndex);

                        final Logger log = LoggerFactory.getLogger(SIFTPointMatchClient.class);

                        final CanvasDataCache dataCache =
                                CanvasDataCache.getSharedCache(broadcastCacheMaxKilobytes.getValue(),
                                                               broadcastFeatureLoader.getValue());
                        final CanvasFeatureMatcher featureMatcher = broadcastFeatureMatcher.getValue();

                        final List<CanvasMatches> matchList = new ArrayList<>();
                        int pairCount = 0;

                        OrderedCanvasIdPair pair;
                        CanvasId p;
                        CanvasId q;
                        List<Feature> pFeatures;
                        List<Feature> qFeatures;
                        CanvasFeatureMatchResult matchResult;
                        Matches inlierMatches;
                        while (pairIterator.hasNext()) {

                            pair = pairIterator.next();
                            pairCount++;

                            p = pair.getP();
                            q = pair.getQ();

                            pFeatures = dataCache.getFeatureList(p);
                            qFeatures = dataCache.getFeatureList(q);

                            log.info("derive matches between {} and {}", p, q);

                            matchResult = featureMatcher.deriveMatchResult(pFeatures, qFeatures);

                            inlierMatches = matchResult.getInlierMatches(renderScale, p.getClipOffsets(), q.getClipOffsets());

                            if (inlierMatches.getWs().length > 0) {
                                matchList.add(new CanvasMatches(p.getGroupId(), p.getId(),
                                                                q.getGroupId(), q.getId(),
                                                                inlierMatches));
                            }
                        }

                        log.info("rddMatches: derived matches for {} out of {} pairs, cache stats are {}",
                                 matchList.size(), pairCount, dataCache.stats());

                        return matchList.iterator();
                    }
                },
                true
        );

        final JavaRDD<Integer> rddSavedMatchPairCounts = rddMatches.mapPartitionsWithIndex(
                new MatchStorageFunction(parameters.baseDataUrl,
                                         parameters.owner,
                                         parameters.collection),
                true
        );

        final int numPartitions = rddSavedMatchPairCounts.getNumPartitions();

        LOG.info("run: {} partitions, debug string is: \n{}",
                 numPartitions, rddSavedMatchPairCounts.toDebugString());

        final List<Integer> matchPairCountList = rddSavedMatchPairCounts.collect();

        LOG.info("run: collected stats");

        long totalSaved = 0;
        for (final Integer matchCount : matchPairCountList) {
            totalSaved += matchCount;
        }

        final long totalProcessed = renderableCanvasIdPairs.size();
        final int percentSaved = (int) ((totalSaved / (double) totalProcessed) * 100);

        LOG.info("run: saved matches for {} out of {} pairs ({}%) on {} partitions",
                 totalSaved, totalProcessed, percentSaved, matchPairCountList.size());
    }

    private CanvasFeatureExtractor getCanvasFeatureExtractor() {

        final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
        siftParameters.fdSize = parameters.fdSize;
        siftParameters.steps = parameters.steps;

        return new CanvasFeatureExtractor(siftParameters,
                                          parameters.minScale,
                                          parameters.maxScale,
                                          parameters.fillWithNoise);
    }

    private CanvasFeatureMatcher getCanvasFeatureMatcher() {
        return new CanvasFeatureMatcher(parameters.matchRod,
                                        parameters.matchModelType,
                                        parameters.matchIterations,
                                        parameters.matchMaxEpsilon,
                                        parameters.matchMinInlierRatio,
                                        parameters.matchMinNumInliers,
                                        parameters.matchMaxTrust,
                                        parameters.matchMaxNumInliers,
                                        true);
    }

    private static final Logger LOG = LoggerFactory.getLogger(SIFTPointMatchClient.class);
}
