//
// Copyright 2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.privacy.differentialprivacy;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.differentialprivacy.SummaryOuterClass.MechanismType.GAUSSIAN;
import static com.google.differentialprivacy.SummaryOuterClass.MechanismType.LAPLACE;
import static java.lang.Double.NaN;
import static java.lang.Math.max;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.math.Stats;
import com.google.differentialprivacy.SummaryOuterClass.BoundedSumSummary;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests the accuracy of {@link BoundedSum}. The test mocks {@link Noise} instance which generates
 * zero noise.
 *
 * <p>Statistical and DP properties of the algorithm are tested in
 * {@link com.google.privacy.differentialprivacy.statistical.BoundedSumDpTest}.
 */
@RunWith(JUnit4.class)
public class BoundedSumTest {
  private static final double TOLERANCE = 1E-3;
  private static final double EPSILON = 0.123;
  private static final double DELTA = 0.123;
  private static final int NUM_SAMPLES = 100000;
  private static final double LN_3 = Math.log(3.0);
  private static final double ALPHA = 0.152145599;

  @Mock private Noise noise;
  private BoundedSum sum;

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Before
  public void setUp() {
    // Mock the noise mechanism so that it does not add any noise.
    when(noise.addNoise(anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble()))
        .thenAnswer(invocation -> invocation.getArguments()[0]);
    // Tests that use serialization need to access to the type of the noise they use. Because the
    // tests don't rely on a specific noise type, we arbitrarily return Gaussian.
    when(noise.getMechanismType()).thenReturn(GAUSSIAN);
    when(noise.computeConfidenceInterval(
        anyLong(), anyInt(), anyLong(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ConfidenceInterval.create(0.0, 0.0));
    when(noise.computeConfidenceInterval(
        anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ConfidenceInterval.create(0.0, 0.0));

    sum =
        BoundedSum.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(1)
            .maxContributionsPerPartition(1)
            // The lower and upper bounds are arbitrarily chosen negative and positive values.
            .lower(-Double.MAX_VALUE)
            .upper(Double.MAX_VALUE)
            .build();
  }

  @Test
  public void addEntry() {
    sum.addEntry(1.0);
    sum.addEntry(2.0);
    sum.addEntry(3.0);
    sum.addEntry(4.0);

    assertThat(sum.computeResult()).isEqualTo(10.0);
  }

  @Test
  public void addEntries() {
    sum.addEntries(Arrays.asList(1.0, 2.0, 3.0, 4.0));
    assertThat(sum.computeResult()).isEqualTo(10.0);
  }

  @Test
  public void addEntry_Nan_ignored() {
    sum.addEntry(NaN);
    sum.addEntry(2);
    assertThat(sum.computeResult()).isEqualTo(2.0);
  }

  // An attempt to compute the sum several times should result in an exception.
  @Test
  public void computeResult_multipleCalls_throwsException() {
    sum.computeResult();
    assertThrows(IllegalStateException.class, () -> sum.computeResult());
  }

  // Input values should be clamped to the upper and lower bounds.
  @Test
  public void addEntry_clampsInput() {
    sum =
        BoundedSum.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(1)
            .lower(0)
            .upper(1)
            .build();

    sum.addEntry(-1.0); // should be clamped to 0
    sum.addEntry(1.0); // should not be clamped
    sum.addEntry(10.0); // should be clamped to 1

    // 0 + 1 + 1
    assertThat(sum.computeResult()).isEqualTo(2);
  }

  @Test
  public void computeResult_callsNoiseCorrectly() {
    double value = 0.5;
    int l0Sensitivity = 1;
    sum =
        BoundedSum.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(l0Sensitivity)
            .maxContributionsPerPartition(5)
            .lower(0)
            .upper(100)
            .build();
    sum.addEntry(value);
    sum.computeResult();

    verify(noise)
        .addNoise(
            eq(value),
            eq(l0Sensitivity),
            eq(/* lower = 0, upper = 100, maxContributionsPerPartition = 5 =>
             lInfSensitivity = max(abs(0), abs(100)) * 5 = 500 */ 500.0),
            eq(EPSILON),
            eq(DELTA));
  }

  @Test
  public void computeResult_addsNoise() {
    // Mock the noise mechanism so that it always generates 100.0.
    when(noise.addNoise(anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble()))
        .thenAnswer(invocation -> (double) invocation.getArguments()[0] + 100.0);
    sum =
        BoundedSum.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(1)
            .lower(0)
            .upper(1000)
            .build();

    sum.addEntry(10);
    assertThat(sum.computeResult()).isEqualTo(110); // value (10) + noise (100) = 110
  }

  // The current implementation of BoundedSum only supports double as input.
  // This test verifies that, if the lower bound is the smallest possible integer (represented as
  // double), then the L_Inf sensitivity calculation does not overflow.
  @Test
  public void lowerBoundMinInteger_doesntOverflow() {
    sum =
        BoundedSum.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(1)
            .maxContributionsPerPartition(1)
            .lower(Integer.MIN_VALUE)
            .upper(0)
            .build();

    sum.computeResult();
    // BoundedSum first calculates L_Inf sensitivity and then passes it to the noise.
    // Verify that L_Inf sensitivity does not overflow and that
    // the noise generation is called with
    // L_Inf sensitivity == lowerBound * maxContributionsPerPartition ==
    // -(double)Integer.MIN_VALUE.
    // More precisely:
    // L_Inf sensitivity =
    // max(abs(lower), abs(upper)) * maxContributionsPerPartition =
    // max(-Integer.MIN_VALUE, 0) = -Integer.MIN_VALUE.
    verify(noise)
        .addNoise(anyDouble(), anyInt(), eq(-(double) Integer.MIN_VALUE), anyDouble(), anyDouble());
  }

  @Test
  public void getSerializableSummary_copiesPartialSumCorrectly() {
    sum.addEntry(10.0);
    sum.addEntry(10.0);

    BoundedSumSummary summary = getSummary(sum);
    assertThat(summary.getPartialSum().getFloatValue()).isEqualTo(20.0);
  }

  @Test
  public void getSerializableSummary_copiesZeroSumCorrectly() {
    BoundedSumSummary summary = getSummary(sum);
    assertThat(summary.getPartialSum().getFloatValue()).isEqualTo(0.0);
  }

  @Test
  public void getSerializableSummary_copiesMaxDoubleSumCorrectly() {
    sum.addEntry(Double.MAX_VALUE);

    BoundedSumSummary summary = getSummary(sum);
    assertThat(summary.getPartialSum().getFloatValue()).isEqualTo(Double.MAX_VALUE);
  }

  @Test
  public void getSerializableSummary_copiesMinDoubleSumCorrectly() {
    sum.addEntry(Double.MIN_VALUE);

    BoundedSumSummary summary = getSummary(sum);
    assertThat(summary.getPartialSum().getFloatValue()).isEqualTo(Double.MIN_VALUE);
  }

  @Test
  public void getSerializableSummary_copiesNegativeSumCorrectly() {
    sum.addEntry(-5.0);
    BoundedSumSummary summary = getSummary(sum);
    assertThat(summary.getPartialSum().getFloatValue()).isEqualTo(-5);
  }

  @Test
  public void getSerializableSummary_copiesPositiveSumCorrectly() {
    sum.addEntry(5);
    BoundedSumSummary summary = getSummary(sum);
    assertThat(summary.getPartialSum().getFloatValue()).isEqualTo(5.0);
  }

  @Test
  public void getSerializableSummary_calledAfterComputeResult_throwsException() {
    sum.computeResult();
    assertThrows(IllegalStateException.class, () -> sum.getSerializableSummary());
  }

  @Test
  public void getSerializableSummary_twoCalls_throwsException() {
    sum.getSerializableSummary();
    assertThrows(IllegalStateException.class, () -> sum.getSerializableSummary());
  }

  @Test
  public void computeResult_calledAfterSerialize_throwsException() {
    sum.getSerializableSummary();
    assertThrows(IllegalStateException.class, () -> sum.computeResult());
  }

  @Test
  public void getSerializableSummary_copiesEpsilonCorrectly() {
    sum = getBoundedSumBuilderWithFields().epsilon(EPSILON).build();
    BoundedSumSummary summary = getSummary(sum);
    assertThat(summary.getEpsilon()).isEqualTo(EPSILON);
  }

  @Test
  public void getSerializableSummary_copiesDeltaCorrectly() {
    sum = getBoundedSumBuilderWithFields().delta(DELTA).build();
    BoundedSumSummary summary = getSummary(sum);
    assertThat(summary.getDelta()).isEqualTo(DELTA);
  }

  @Test
  public void getSerializableSummary_copiesGaussianNoiseCorrectly() {
    sum = getBoundedSumBuilderWithFields().noise(new GaussianNoise()).build();
    BoundedSumSummary summary = getSummary(sum);
    assertThat(summary.getMechanismType()).isEqualTo(GAUSSIAN);
  }

  @Test
  public void getSerializableSummary_copiesLaplaceNoiseCorrectly() {
    sum = getBoundedSumBuilderWithFields().noise(new LaplaceNoise()).delta(null).build();
    BoundedSumSummary summary = getSummary(sum);
    assertThat(summary.getMechanismType()).isEqualTo(LAPLACE);
  }

  @Test
  public void getSerializableSummary_copiesMaxPartitionsContributedCorrectly() {
    int maxPartitionsContributed = 150;
    sum =
        getBoundedSumBuilderWithFields().maxPartitionsContributed(maxPartitionsContributed).build();
    BoundedSumSummary summary = getSummary(sum);
    assertThat(summary.getMaxPartitionsContributed()).isEqualTo(maxPartitionsContributed);
  }

  @Test
  public void getSerializableSummary_copiesMaxContributionsPerPartitionCorrectly() {
    int maxContributionsPerPartition = 150;
    sum =
        getBoundedSumBuilderWithFields()
            .maxContributionsPerPartition(maxContributionsPerPartition)
            .build();
    BoundedSumSummary summary = getSummary(sum);
    assertThat(summary.getMaxContributionsPerPartition()).isEqualTo(maxContributionsPerPartition);
  }

  @Test
  public void getSerializableSummary_copiesLowerCorrectly() {
    double lower = -0.1;
    sum = getBoundedSumBuilderWithFields().lower(lower).build();
    BoundedSumSummary summary = getSummary(sum);
    assertThat(summary.getLower()).isEqualTo(lower);
  }

  @Test
  public void getSerializableSummary_copiesUpperCorrectly() {
    double upper = 0.1;
    sum = getBoundedSumBuilderWithFields().upper(upper).build();
    BoundedSumSummary summary = getSummary(sum);
    assertThat(summary.getUpper()).isEqualTo(upper);
  }

  @Test
  public void merge_basicExample_sumsValues() {
    BoundedSum targetSum = getBoundedSumBuilderWithFields().build();
    BoundedSum sourceSum = getBoundedSumBuilderWithFields().build();

    targetSum.addEntry(1);
    sourceSum.addEntry(1);

    targetSum.mergeWith(sourceSum.getSerializableSummary());

    assertThat(targetSum.computeResult()).isEqualTo(2);
  }

  @Test
  public void merge_calledTwice_sumsValues() {
    BoundedSum targetSum = getBoundedSumBuilderWithFields().build();
    BoundedSum sourceSum1 = getBoundedSumBuilderWithFields().build();
    BoundedSum sourceSum2 = getBoundedSumBuilderWithFields().build();

    targetSum.addEntry(1);
    sourceSum1.addEntry(2);
    sourceSum2.addEntry(3);

    targetSum.mergeWith(sourceSum1.getSerializableSummary());
    targetSum.mergeWith(sourceSum2.getSerializableSummary());

    assertThat(targetSum.computeResult()).isEqualTo(6);
  }

  @Test
  public void merge_nullDelta_noException() {
    BoundedSum targetSum =
        getBoundedSumBuilderWithFields().noise(new LaplaceNoise()).delta(null).build();
    BoundedSum sourceSum =
        getBoundedSumBuilderWithFields().noise(new LaplaceNoise()).delta(null).build();
    // No exception should be thrown.
    targetSum.mergeWith(sourceSum.getSerializableSummary());
  }

  @Test
  public void merge_differentEpsilon_throwsException() {
    BoundedSum targetSum = getBoundedSumBuilderWithFields().epsilon(EPSILON).build();
    BoundedSum sourceSum = getBoundedSumBuilderWithFields().epsilon(2 * EPSILON).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> targetSum.mergeWith(sourceSum.getSerializableSummary()));
  }

  @Test
  public void merge_differentDelta_throwsException() {
    BoundedSum targetSum = getBoundedSumBuilderWithFields().delta(DELTA).build();
    BoundedSum sourceSum = getBoundedSumBuilderWithFields().delta(2 * DELTA).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> targetSum.mergeWith(sourceSum.getSerializableSummary()));
  }

  @Test
  public void merge_differentNoise_throwsException() {
    BoundedSum targetSum =
        getBoundedSumBuilderWithFields().noise(new LaplaceNoise()).delta(null).build();
    BoundedSum sourceSum = getBoundedSumBuilderWithFields().noise(new GaussianNoise()).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> targetSum.mergeWith(sourceSum.getSerializableSummary()));
  }

  @Test
  public void merge_differentMaxPartitionsContributed_throwsException() {
    BoundedSum targetSum = getBoundedSumBuilderWithFields().maxPartitionsContributed(1).build();
    BoundedSum sourceSum = getBoundedSumBuilderWithFields().maxPartitionsContributed(2).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> targetSum.mergeWith(sourceSum.getSerializableSummary()));
  }

  @Test
  public void merge_differentMaxContributionsPerPartition_throwsException() {
    BoundedSum targetSum = getBoundedSumBuilderWithFields().maxContributionsPerPartition(1).build();
    BoundedSum sourceSum = getBoundedSumBuilderWithFields().maxContributionsPerPartition(2).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> targetSum.mergeWith(sourceSum.getSerializableSummary()));
  }

  @Test
  public void merge_differentLowerBounds_throwsException() {
    BoundedSum targetSum = getBoundedSumBuilderWithFields().lower(-1).build();
    BoundedSum sourceSum = getBoundedSumBuilderWithFields().lower(-100).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> targetSum.mergeWith(sourceSum.getSerializableSummary()));
  }

  @Test
  public void merge_differentUpperBounds_throwsException() {
    BoundedSum targetSum = getBoundedSumBuilderWithFields().upper(1).build();
    BoundedSum sourceSum = getBoundedSumBuilderWithFields().upper(100).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> targetSum.mergeWith(sourceSum.getSerializableSummary()));
  }

  @Test
  public void merge_calledAfterComputeResult_onTargetSum_throwsException() {
    BoundedSum targetSum = getBoundedSumBuilderWithFields().build();
    BoundedSum sourceSum = getBoundedSumBuilderWithFields().build();

    targetSum.computeResult();
    assertThrows(
        IllegalStateException.class, () -> targetSum.mergeWith(sourceSum.getSerializableSummary()));
  }

  @Test
  public void merge_calledAfterComputeResult_onSourceSum_throwsException() {
    BoundedSum targetSum = getBoundedSumBuilderWithFields().build();
    BoundedSum sourceSum = getBoundedSumBuilderWithFields().build();

    sourceSum.computeResult();
    assertThrows(
        IllegalStateException.class, () -> targetSum.mergeWith(sourceSum.getSerializableSummary()));
  }

  @Test
  public void addNoise_gaussianNoiseDefaultParametersEmptySum_isUnbiased() {
    BoundedSum.Params.Builder sumBuilder =
        BoundedSum.builder()
            .epsilon(LN_3)
            .delta(0.00001)
            .maxPartitionsContributed(1)
            .lower(0.0)
            .upper(1.0)
            .noise(new GaussianNoise());

    testForBias(sumBuilder, /* rawEntry */ 0.0, /* variance */ 11.735977);
  }

  @Test
  public void addNoise_gaussianNoiseDifferentEpsilonEmptySum_isUnbiased() {
    BoundedSum.Params.Builder sumBuilder =
        BoundedSum.builder()
            .epsilon(2.0 * LN_3)
            .delta(0.00001)
            .maxPartitionsContributed(1)
            .lower(0.0)
            .upper(1.0)
            .noise(new GaussianNoise());

    testForBias(sumBuilder, /* rawEntry */ 0.0, /* variance */ 3.3634987);
  }

  @Test
  public void addNoise_gaussianNoiseDifferentDeltaEmptySum_isUnbiased() {
    BoundedSum.Params.Builder sumBuilder =
        BoundedSum.builder()
            .epsilon(LN_3)
            .delta(0.01)
            .maxPartitionsContributed(1)
            .lower(0.0)
            .upper(1.0)
            .noise(new GaussianNoise());

    testForBias(sumBuilder, /* rawEntry */ 0.0, /* variance */ 3.0625);
  }

  @Test
  public void addNoise_gaussianNoiseDifferentContributionBoundEmptySum_isUnbiased() {
    BoundedSum.Params.Builder sumBuilder =
        BoundedSum.builder()
            .epsilon(LN_3)
            .delta(0.00001)
            .maxPartitionsContributed(25)
            .lower(0.0)
            .upper(1.0)
            .noise(new GaussianNoise());

    testForBias(sumBuilder, /* rawEntry */ 0.0, /* variance */ 293.399425);
  }

  @Test
  public void addNoise_gaussianNoiseDifferentEntryBoundsEmptySum_isUnbiased() {
    BoundedSum.Params.Builder sumBuilder =
        BoundedSum.builder()
            .epsilon(LN_3)
            .delta(0.00001)
            .maxPartitionsContributed(1)
            .lower(-0.5)
            .upper(0.0)
            .noise(new GaussianNoise());

    testForBias(sumBuilder, /* rawEntry */ 0.0, /* variance */ 2.93399425);
  }

  @Test
  public void addNoise_gaussianNoiseDefaultParametersPositiveEntry_isUnbiased() {
    BoundedSum.Params.Builder sumBuilder =
        BoundedSum.builder()
            .epsilon(LN_3)
            .delta(0.00001)
            .maxPartitionsContributed(1)
            .lower(0.0)
            .upper(1.0)
            .noise(new GaussianNoise());

    testForBias(sumBuilder, /* rawEntry */ 1.0, /* variance */ 11.735977);
  }

  @Test
  public void addNoise_gaussianNoiseDefaultParametersNegativeEntry_isUnbiased() {
    BoundedSum.Params.Builder sumBuilder =
        BoundedSum.builder()
            .epsilon(LN_3)
            .delta(0.00001)
            .maxPartitionsContributed(1)
            .lower(-1.0)
            .upper(0.0)
            .noise(new GaussianNoise());

    testForBias(sumBuilder, /* rawEntry */ -1.0, /* variance */ 11.735977);
  }

    @Test
  public void addNoise_laplaceNoiseDefaultParametersEmptySum_isUnbiased() {
    BoundedSum.Params.Builder sumBuilder =
        BoundedSum.builder()
            .epsilon(LN_3)
            .maxPartitionsContributed(1)
            .lower(0.0)
            .upper(1.0)
            .noise(new LaplaceNoise());

    testForBias(sumBuilder, /* rawEntry */ 0.0, /* variance */ 2.0 / (LN_3 * LN_3));
  }

  @Test
  public void addNoise_laplaceNoiseDifferentEpsilonEmptySum_isUnbiased() {
    BoundedSum.Params.Builder sumBuilder =
        BoundedSum.builder()
            .epsilon(2.0 * LN_3)
            .maxPartitionsContributed(1)
            .lower(0.0)
            .upper(1.0)
            .noise(new LaplaceNoise());

    testForBias(sumBuilder, /* rawEntry */ 0.0, /* variance */ 2.0 / (4.0 * LN_3 * LN_3));
  }

  @Test
  public void addNoise_laplaceNoiseDifferentContributionBoundEmptySum_isUnbiased() {
    BoundedSum.Params.Builder sumBuilder =
        BoundedSum.builder()
            .epsilon(LN_3)
            .maxPartitionsContributed(25)
            .lower(0.0)
            .upper(1.0)
            .noise(new LaplaceNoise());

    testForBias(sumBuilder, /* rawEntry */ 0.0, /* variance */ 2.0 * 625.0 / (LN_3 * LN_3));
  }

  @Test
  public void addNoise_laplaceNoiseDifferentEntryBoundsEmptySum_isUnbiased() {
    BoundedSum.Params.Builder sumBuilder =
        BoundedSum.builder()
            .epsilon(LN_3)
            .maxPartitionsContributed(1)
            .lower(-0.5)
            .upper(0.0)
            .noise(new LaplaceNoise());

    testForBias(sumBuilder, /* rawEntry */ 0.0, /* variance */ 2.0 / (4.0 * LN_3 * LN_3));
  }

  @Test
  public void addNoise_laplaceNoiseDefaultParametersPositiveEntry_isUnbiased() {
    BoundedSum.Params.Builder sumBuilder =
        BoundedSum.builder()
            .epsilon(LN_3)
            .maxPartitionsContributed(1)
            .lower(0.0)
            .upper(1.0)
            .noise(new LaplaceNoise());

    testForBias(sumBuilder, /* rawEntry */ 1.0, /* variance */ 2.0 / (LN_3 * LN_3));
  }

  @Test
  public void addNoise_laplaceNoiseDefaultParametersNegativeEntry_isUnbiased() {
    BoundedSum.Params.Builder sumBuilder =
        BoundedSum.builder()
            .epsilon(LN_3)
            .maxPartitionsContributed(1)
            .lower(-1.0)
            .upper(0.0)
            .noise(new LaplaceNoise());

    testForBias(sumBuilder, /* rawEntry */ -1.0, /* variance */ 2.0 / (LN_3 * LN_3));
  }

  private BoundedSum.Params.Builder getBoundedSumBuilderWithFields() {
    return BoundedSum.builder()
        .epsilon(EPSILON)
        .delta(DELTA)
        .noise(noise)
        .maxPartitionsContributed(1)
        // lower, upper and, maxContributionsPerPartition have arbitrarily chosen values.
        .maxContributionsPerPartition(10)
        .lower(-10)
        .upper(10);
  }

  /**
   * Note that {@link BoundedSumSummary} isn't visible to the actual clients, who only see an opaque
   * {@code byte[]} blob. Here, we parse said blob to perform whitebox testing, to verify some
   * expectations of the blob's content. We do this because achieving good coverage with pure
   * behaviour testing (i.e., blackbox testing) isn't possible.
   */
  private static BoundedSumSummary getSummary(BoundedSum sum) {
    byte[] nonParsedSummary = sum.getSerializableSummary();
    try {
      return BoundedSumSummary.parseFrom(nonParsedSummary);
    } catch (InvalidProtocolBufferException pbe) {
      throw new IllegalArgumentException(pbe);
    }
  }

  private static void testForBias(
      BoundedSum.Params.Builder sumBuilder, double rawEntry, double variance) {
    ImmutableList.Builder<Double> samples = ImmutableList.builder();
    for (int i = 0; i < NUM_SAMPLES; i++) {
      BoundedSum sum = sumBuilder.build();
      sum.addEntry(rawEntry);
      samples.add(sum.computeResult());
    }
    Stats stats = Stats.of(samples.build());

    // The tolerance is chosen according to the 99.9995% quantile of the anticipated distributions
    // of the sample mean. Thus, the test falsely rejects with a probability of 10^-5.
    double sampleTolerance = 4.41717 * Math.sqrt(variance / NUM_SAMPLES);
    // The DP count is considered unbiased if the expeted value (approximated by stats.mean()) is
    // equal to the raw count.
    assertThat(stats.mean()).isWithin(sampleTolerance).of(rawEntry);
  }

  @Test
  public void computeConfidenceInterval_negativeSumBounds_noClamping() {
    sum = getBoundedSumBuilderWithFields().lower(-8.0).upper(-2.0).build();
    when(noise.computeConfidenceInterval(
        anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ConfidenceInterval.create(-5.0, -3.0));
    sum.computeResult();

    assertThat(sum.computeConfidenceInterval(ALPHA))
        .isEqualTo(ConfidenceInterval.create(-5.0, -3.0));
  }

  @Test
  public void computeConfidenceInterval_negativeSumBounds_clampsPositiveInterval() {
    sum = getBoundedSumBuilderWithFields().lower(-5.0).upper(-1.0).build();
    when(noise.computeConfidenceInterval(
        anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ConfidenceInterval.create(-5.0, 3.0));
    sum.computeResult();

    assertThat(sum.computeConfidenceInterval(ALPHA))
        .isEqualTo(ConfidenceInterval.create(-5.0, 0.0));
  }

  @Test
  public void computeConfidenceInterval_negativeSumBounds_clampsInterval() {
    sum = getBoundedSumBuilderWithFields().lower(-5.0).upper(-1.0).build();
    when(noise.computeConfidenceInterval(
        anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ConfidenceInterval.create(3.0, 5.0));
    sum.computeResult();

    assertThat(sum.computeConfidenceInterval(ALPHA))
        .isEqualTo(ConfidenceInterval.create(0.0, 0.0));
  }

  @Test
  public void computeConfidenceInterval_positiveSumBounds_noClamping() {
    sum = getBoundedSumBuilderWithFields().lower(1.0).upper(5.0).build();
    when(noise.computeConfidenceInterval(
        anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ConfidenceInterval.create(1.0, 3.0));
    sum.computeResult();

    assertThat(sum.computeConfidenceInterval(ALPHA))
        .isEqualTo(ConfidenceInterval.create(1.0, 3.0));
  }

  @Test
  public void computeConfidenceInterval_positiveSumBounds_clampsNegativeInterval() {
    sum = getBoundedSumBuilderWithFields().lower(1.0).upper(5.0).build();
    when(noise.computeConfidenceInterval(
        anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ConfidenceInterval.create(-5.0, 3.0));
    sum.computeResult();

    assertThat(sum.computeConfidenceInterval(ALPHA))
        .isEqualTo(ConfidenceInterval.create(0.0, 3.0));
  }

  @Test
  public void computeConfidenceInterval_positiveSumBounds_clampsInterval() {
    sum = getBoundedSumBuilderWithFields().lower(1.0).upper(5.0).build();
    when(noise.computeConfidenceInterval(
        anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ConfidenceInterval.create(-3.0, -1.0));
    sum.computeResult();

    assertThat(sum.computeConfidenceInterval(ALPHA))
        .isEqualTo(ConfidenceInterval.create(0.0, 0.0));
  }

  @Test
  public void computeConfidenceInterval_differentSumBoundsSigns_noClamping() {
    sum = getBoundedSumBuilderWithFields().lower(-1.0).upper(5.0).build();
    when(noise.computeConfidenceInterval(
        anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ConfidenceInterval.create(-5.0, 3.0));
    sum.computeResult();

    assertThat(sum.computeConfidenceInterval(ALPHA))
        .isEqualTo(ConfidenceInterval.create(-5.0, 3.0));
  }

  @Test
  public void computeConfidenceInterval_infiniteSumBounds_clampsNegativeInterval() {
    sum = getBoundedSumBuilderWithFields().lower(1.0).upper(5.0).build();
    when(noise.computeConfidenceInterval(
        anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ConfidenceInterval.create(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
    sum.computeResult();

    assertThat(sum.computeConfidenceInterval(ALPHA))
        .isEqualTo(ConfidenceInterval.create(0.0, Double.POSITIVE_INFINITY));
  }

  @Test
  public void computeConfidenceInterval_forGaussianNoise() {
    // Mock the noise mechanism.
    when(noise.computeConfidenceInterval(
        anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
        .thenAnswer(
            invocation ->
                new GaussianNoise()
                    .computeConfidenceInterval(
                        (Double) invocation.getArguments()[0],
                        (Integer) invocation.getArguments()[1],
                        (Double) invocation.getArguments()[2],
                        (Double) invocation.getArguments()[3],
                        (Double) invocation.getArguments()[4],
                        (Double) invocation.getArguments()[5]));
    sum = getBoundedSumBuilderWithFields().lower(1.0).upper(5.0).build();
    sum.addEntry(1);
    sum.computeResult();
    ConfidenceInterval confInt = sum.computeConfidenceInterval(ALPHA);

    assertWithMessage("Lower bound is not precise: actual = %s, expected = %s.", confInt.lowerBound(), 0.0 )
        .that(approxEqual(confInt.lowerBound(), 0.0))
        .isTrue();
    assertWithMessage("Upper bound is not precise: actual = %s, expected = %s.", confInt.upperBound(), 167.68)
        .that(approxEqual(confInt.upperBound(), 167.68))
        .isTrue();
  }

  @Test
  public void computeConfidenceInterval_forLaplaceNoise() {
    // Mock the noise mechanism. Since noise is not Laplace, nor Gaussian, delta will be passed
    // as null, in order to pass the checks.
    when(noise.computeConfidenceInterval(
        anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
        .thenAnswer(
            invocation ->
                new LaplaceNoise()
                    .computeConfidenceInterval(
                        (Double) invocation.getArguments()[0],
                        (Integer) invocation.getArguments()[1],
                        (Double) invocation.getArguments()[2],
                        (Double) invocation.getArguments()[3],
                        null,
                        (Double) invocation.getArguments()[5]));
    sum = getBoundedSumBuilderWithFields().lower(1.0).upper(10.0).build();
    sum.addEntry(1);
    sum.computeResult();
    ConfidenceInterval confInt = sum.computeConfidenceInterval(ALPHA);

    assertWithMessage("Lower bound is not precise: actual = %s, expected = %s.", confInt.lowerBound(), 0.0 )
        .that(approxEqual(confInt.lowerBound(), 0.0))
        .isTrue();
    assertWithMessage("Upper bound is not precise: actual = %s, expected = %s.", confInt.upperBound(), 1531.827)
        .that(approxEqual(confInt.upperBound(), 1531.827))
        .isTrue();
  }

  @Test
  public void computeConfidenceInterval_computeResultWasNotCalled_forLong_throwsException() {
    sum = getBoundedSumBuilderWithFields().lower(1.0).upper(5.0).build();
    sum.addEntry(1.0);
    IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
      sum.computeConfidenceInterval(ALPHA);
    });
    assertThat(exception)
        .hasMessageThat()
        .startsWith("computeResult must be called before calling computeConfidenceInterval.");
  }

  @Test
  public void computeConfidenceInterval_computeResultWasNotCalled_forDouble_throwsException() {
    sum = getBoundedSumBuilderWithFields().lower(1.0).upper(5.0).build();
    sum.addEntry(1.0);
    IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
      sum.computeConfidenceInterval(ALPHA);
    });
    assertThat(exception)
        .hasMessageThat()
        .startsWith("computeResult must be called before calling computeConfidenceInterval.");
  }

  @Test
  public void computeConfidenceIntervals_defaultParameters_callsNoiseCorrectly() {
    sum = getBoundedSumBuilderWithFields().lower(0.0).upper(100.0).build();
    sum.computeResult();
    sum.computeConfidenceInterval(ALPHA);
    verify(noise)
        .computeConfidenceInterval(
            eq(0.0d), // sum of added entries = 0.0
            eq(/* l0Sensitivity = maxPartitionsContributed = 1 */ 1),
            eq(/* lInfSensitivity = max(lower, upper) * maxContributionsPerPartition = 10.0 * 100 = 1000 */ 1000.0),
            eq(EPSILON),
            eq(DELTA),
            eq(ALPHA));
  }

  private static boolean approxEqual(double a, double b) {
    double maxMagnitude = max(Math.abs(a), Math.abs(b));
    return Math.abs(a - b) <= TOLERANCE * maxMagnitude;
  }
}
