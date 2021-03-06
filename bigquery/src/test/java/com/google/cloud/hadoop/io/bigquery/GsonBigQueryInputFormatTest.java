package com.google.cloud.hadoop.io.bigquery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobReference;
import com.google.api.services.bigquery.model.JobStatus;
import com.google.api.services.bigquery.model.Table;
import com.google.api.services.bigquery.model.TableReference;
import com.google.cloud.hadoop.fs.gcs.InMemoryGoogleHadoopFileSystem;
import com.google.cloud.hadoop.testing.CredentialConfigurationUtil;
import com.google.cloud.hadoop.util.LogUtil;
import com.google.common.collect.ImmutableList;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.input.LineRecordReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * Unit tests for GsonBigQueryInputFormat.
 */
@RunWith(JUnit4.class)
public class GsonBigQueryInputFormatTest {

  // Sample text values for tests.
  private Text value1 = new Text("{'title':'Test1','value':'test_1'}");
  private Text value2 = new Text("{'title':'Test2','value':'test_2'}");

  // GoogleHadoopGlobalRootedFileSystem to use.
  private InMemoryGoogleHadoopFileSystem ghfs;

  // Hadoop job configuration.
  private Configuration config;

  // Sample projectIds for testing; one for owning the BigQuery jobs, another for the
  // TableReference.
  private String jobProjectId = "google.com:foo-project";
  private String dataProjectId = "publicdata";
  private String intermediateDataset = "test_dataset";
  private String intermediateTable = "test_table";

  // Misc mocks for Bigquery auto-generated API objects.
  private Bigquery mockBigquery;
  private Bigquery.Jobs mockBigqueryJobs;
  private Bigquery.Jobs.Get mockBigqueryJobsGet;
  private Bigquery.Jobs.Insert mockBigqueryJobsInsert;
  private Bigquery.Tables mockBigqueryTables;
  private Bigquery.Tables.Get mockBigqueryTablesGet;
  private Bigquery.Tables.Delete mockBigqueryTablesDelete;

  // JobStatus to return for testing.
  private JobStatus jobStatus;

  // Bigquery Job result to return for testing.
  private Job jobHandle;

  // Sample TableReference for BigQuery.
  private TableReference tableRef;

  private InputFormat mockInputFormat;

  /**
   * Creates an in-memory GHFS.
   *
   * @throws IOException on IOError.
   */
  @Before
  public void setUp() 
      throws IOException {
    GsonBigQueryInputFormat.log.setLevel(LogUtil.Level.DEBUG);

    // Set the Hadoop job configuration.
    config = InMemoryGoogleHadoopFileSystem.getSampleConfiguration();
    config.set(BigQueryConfiguration.PROJECT_ID_KEY, jobProjectId);
    config.set(BigQueryConfiguration.INPUT_PROJECT_ID_KEY, dataProjectId);
    config.set(BigQueryConfiguration.INPUT_DATASET_ID_KEY, intermediateDataset);
    config.set(BigQueryConfiguration.INPUT_TABLE_ID_KEY, intermediateTable);
    config.set(BigQueryConfiguration.INPUT_QUERY_KEY, "test_query");
    config.set(BigQueryConfiguration.TEMP_GCS_PATH_KEY, "gs://test_bucket/other_path");
    config.set(
        AbstractBigQueryInputFormat.INPUT_FORMAT_CLASS_KEY,
        GsonBigQueryInputFormat.class.getCanonicalName());
    config.setBoolean(BigQueryConfiguration.DELETE_INTERMEDIATE_TABLE_KEY, true);
    config.setBoolean(BigQueryConfiguration.DELETE_EXPORT_FILES_FROM_GCS_KEY, true);
    config.setBoolean(BigQueryConfiguration.ENABLE_SHARDED_EXPORT_KEY, false);

    CredentialConfigurationUtil.addTestConfigurationSettings(config);

    // Create a GoogleHadoopGlobalRootedFileSystem to use to initialize and write to
    // the in-memory GcsFs.
    ghfs = new InMemoryGoogleHadoopFileSystem();

    JobReference fakeJobReference = new JobReference();
    fakeJobReference.setJobId("bigquery-job-1234");

    mockInputFormat = mock(InputFormat.class);

    // Create the job result.
    jobHandle = new Job();
    jobStatus = new JobStatus();
    jobStatus.setState("DONE");
    jobStatus.setErrorResult(null);
    jobHandle.setStatus(jobStatus);
    jobHandle.setJobReference(fakeJobReference);

    // Mock BigQuery.
    mockBigquery = mock(Bigquery.class);
    mockBigqueryJobs = mock(Bigquery.Jobs.class);
    mockBigqueryJobsGet = mock(Bigquery.Jobs.Get.class);
    mockBigqueryJobsInsert = mock(Bigquery.Jobs.Insert.class);
    mockBigqueryTables = mock(Bigquery.Tables.class);
    mockBigqueryTablesGet = mock(Bigquery.Tables.Get.class);
    mockBigqueryTablesDelete = mock(Bigquery.Tables.Delete.class);

    // Mocks for Bigquery jobs.
    when(mockBigquery.jobs())
        .thenReturn(mockBigqueryJobs);

    // Mock getting Bigquery job.
    when(mockBigqueryJobs.get(jobProjectId, fakeJobReference.getJobId()))
        .thenReturn(mockBigqueryJobsGet);
    when(mockBigqueryJobsGet.execute())
        .thenReturn(jobHandle);

    // Mock inserting Bigquery job.
    when(mockBigqueryJobs.insert(any(String.class), any(Job.class)))
        .thenReturn(mockBigqueryJobsInsert);
    when(mockBigqueryJobsInsert.execute())
        .thenReturn(jobHandle);

    // Mocks for Bigquery tables.
    when(mockBigquery.tables())
        .thenReturn(mockBigqueryTables);

    // Mocks for getting Bigquery table.
    when(mockBigqueryTables.get(any(String.class), any(String.class), any(String.class)))
        .thenReturn(mockBigqueryTablesGet);

    // Create table reference.
    tableRef = new TableReference();
    tableRef.setProjectId(dataProjectId);
    tableRef.setDatasetId("test_dataset");
    tableRef.setTableId("test_table");
  }

  @After
  public void tearDown()
      throws IOException {
    Path tmpPath = new Path(config.get(BigQueryConfiguration.TEMP_GCS_PATH_KEY));
    tmpPath.getFileSystem(config).delete(tmpPath, true);
  }

  /**
   * Tests createRecordReader method of GsonBigQueryInputFormat.
   */
  @Test
  public void testCreateRecordReader() 
      throws IOException, InterruptedException {

    TaskAttemptContext context = Mockito.mock(TaskAttemptContext.class);
    Mockito.when(context.getConfiguration()).thenReturn(config);
    Mockito.when(context.getJobID()).thenReturn(new JobID());

    // Write values to file.
    ByteBuffer buffer = GsonRecordReaderTest.stringToBytebuffer(value1 + "\n" + value2 + "\n");
    Path mockPath = new Path("gs://test_bucket/path/test");
    GsonRecordReaderTest.writeFile(ghfs, mockPath, buffer);

    // Create a new InputSplit containing the values.
    UnshardedInputSplit bqInputSplit = new UnshardedInputSplit(mockPath, 0, 60, new String[0]);

    // Construct GsonBigQueryInputFormat and call createBigQueryRecordReader.
    GsonBigQueryInputFormat gsonBigQueryInputFormat = new GsonBigQueryInputFormat();
    GsonRecordReader recordReader =
        (GsonRecordReader) gsonBigQueryInputFormat.createRecordReader(bqInputSplit, config);
    recordReader.initialize(bqInputSplit, context);

    // Verify BigQueryRecordReader set as expected.
    assertEquals(true, recordReader.nextKeyValue());
    assertEquals(true, recordReader.nextKeyValue());
    assertEquals(false, recordReader.nextKeyValue());
  }

  /**
   * Tests runQuery method of GsonBigQueryInputFormat.
   */
  @Test
  public void testRunQuery()
      throws IOException, InterruptedException {

    // Run runQuery method.
    QueryBasedExport.runQuery(mockBigquery, jobProjectId, tableRef, "test");

    // Verify correct calls to BigQuery are made.
    verify(mockBigqueryJobs).insert(any(String.class), any(Job.class));
    verify(mockBigqueryJobsInsert).execute();
  }

  /**
   * Tests getSplits method of GsonBigQueryInputFormat.
   */
  @Test
  public void testGetSplitsSharded()
      throws IOException, InterruptedException {
    config.setBoolean(BigQueryConfiguration.ENABLE_SHARDED_EXPORT_KEY, true);

    // Make the bytes large enough that we will estimate a large number of shards.
    Table fakeTable = new Table()
        .setNumRows(BigInteger.valueOf(99999L))
        .setNumBytes(1024L * 1024 * 1024 * 8);
    when(mockBigqueryTablesGet.execute())
        .thenReturn(fakeTable);

    // If the hinted map.tasks is smaller than the estimated number of files, then we defer
    // to the hint.
    config.setInt(ShardedExportToCloudStorage.NUM_MAP_TASKS_HINT_KEY, 3);

    // Run getSplits method.
    GsonBigQueryInputFormat gsonBigQueryInputFormat = new GsonBigQueryInputFormatForTest();
    BigQueryJobWrapper wrapper = new BigQueryJobWrapper(config);
    wrapper.setJobID(new JobID());
    List<InputSplit> splits = gsonBigQueryInputFormat.getSplits(wrapper);

    // The base export path should've gotten created.
    Path baseExportPath = new Path(config.get(BigQueryConfiguration.TEMP_GCS_PATH_KEY));
    FileStatus baseStatus = baseExportPath.getFileSystem(config).getFileStatus(baseExportPath);
    assertTrue(baseStatus.isDir());

    assertEquals(3, splits.size());
    for (int i = 0; i < 3; ++i) {
      assertTrue(splits.get(i) instanceof ShardedInputSplit);
      DynamicFileListRecordReader<LongWritable, Text> reader =
          new DynamicFileListRecordReader<>(new DelegateRecordReaderFactory<LongWritable, Text>() {
            @Override
            public RecordReader<LongWritable, Text> createDelegateRecordReader(
                InputSplit split, Configuration configuration)
                throws IOException, InterruptedException {
              return new LineRecordReader();
            }
          });
      TaskAttemptContext context = Mockito.mock(TaskAttemptContext.class);
      Mockito.when(context.getConfiguration()).thenReturn(config);
      reader.initialize(splits.get(i), context);
      Path shardDir = ((ShardedInputSplit) splits.get(i))
          .getShardDirectoryAndPattern()
          .getParent();
      FileStatus shardDirStatus = shardDir.getFileSystem(config).getFileStatus(shardDir);
      assertTrue(shardDirStatus.isDir());
    }

    // Verify correct calls to BigQuery are made.
    verify(mockBigqueryJobs, times(2)).insert(any(String.class), any(Job.class));
    verify(mockBigqueryJobsInsert, times(2)).execute();

    // Make sure we didn't try to delete the table in sharded mode even though
    // DELETE_INTERMEDIATE_TABLE_KEY is true and we had a query.
    verify(mockBigqueryTables, times(1)).get(
        eq(dataProjectId), eq("test_dataset"), eq("test_table"));
    verifyNoMoreInteractions(mockBigqueryTables);
  }

  @Test
  public void testGetSplitsShardedSmall()
      throws IOException, InterruptedException {
    config.setBoolean(BigQueryConfiguration.ENABLE_SHARDED_EXPORT_KEY, true);

    // Make the bytes large enough that we will estimate a large number of shards.
    Table fakeTable = new Table()
        .setNumRows(BigInteger.valueOf(2L))
        .setNumBytes(1L);
    when(mockBigqueryTablesGet.execute())
        .thenReturn(fakeTable);

    // If the hinted map.tasks is smaller than the estimated number of files, then we defer
    // to the hint.
    config.setInt(ShardedExportToCloudStorage.NUM_MAP_TASKS_HINT_KEY, 3);

    // Run getSplits method.
    GsonBigQueryInputFormat gsonBigQueryInputFormat = new GsonBigQueryInputFormatForTest();
    BigQueryJobWrapper wrapper = new BigQueryJobWrapper(config);
    wrapper.setJobID(new JobID());
    List<InputSplit> splits = gsonBigQueryInputFormat.getSplits(wrapper);

    // The base export path should've gotten created.
    Path baseExportPath = new Path(config.get(BigQueryConfiguration.TEMP_GCS_PATH_KEY));
    FileStatus baseStatus = baseExportPath.getFileSystem(config).getFileStatus(baseExportPath);
    assertTrue(baseStatus.isDir());

    assertEquals(2, splits.size());
    for (int i = 0; i < 2; ++i) {
      assertTrue(splits.get(i) instanceof ShardedInputSplit);
    }

    // Verify correct calls to BigQuery are made.
    verify(mockBigqueryJobs, times(2)).insert(any(String.class), any(Job.class));
    verify(mockBigqueryJobsInsert, times(2)).execute();

    // Make sure we didn't try to delete the table in sharded mode even though
    // DELETE_INTERMEDIATE_TABLE_KEY is true and we had a query.
    verify(mockBigqueryTables, times(1)).get(
        eq(dataProjectId), eq("test_dataset"), eq("test_table"));
    verifyNoMoreInteractions(mockBigqueryTables);
  }

  /**
   * Tests getSplits method of GsonBigQueryInputFormat in sharded-export mode.
   */
  @Test
  public void testGetSplitsUnshardedBlocking()
      throws IOException, InterruptedException {
    config.setBoolean(BigQueryConfiguration.ENABLE_SHARDED_EXPORT_KEY, false);

    Table fakeTable = new Table()
        .setNumRows(BigInteger.valueOf(3L))
        .setNumBytes(1024L);
    when(mockBigqueryTablesGet.execute())
        .thenReturn(fakeTable);

    BigQueryJobWrapper wrapper = new BigQueryJobWrapper(config);
    wrapper.setJobID(new JobID());

    when(mockInputFormat.getSplits(eq(wrapper)))
        .thenReturn(ImmutableList.of(
            new FileSplit(new Path("file1"), 0, 100, new String[0])));

    // TODO (angusdavis): Inject a ExportProvider or something similar to allow us to inject a
    // mock export and actually test getSplits instead of what getSplits calls.
    // Running GsonBigQueryInputFomrat#getSplits doesn't allow us to inject a mockInputFormat.
    // We'll cut out the middle man.
    UnshardedExportToCloudStorage exportToCloudStorage =
        new UnshardedExportToCloudStorage(
            config,
            AbstractBigQueryInputFormat.extractExportPathRoot(config, new JobID()),
            ExportFileFormat.LINE_DELIMITED_JSON,
            mockBigquery,
            jobProjectId,
            tableRef,
            mockInputFormat);

    QueryBasedExport queryBasedExport =
        new QueryBasedExport(
            exportToCloudStorage,
            config.get(BigQueryConfiguration.INPUT_QUERY_KEY),
            jobProjectId,
            mockBigquery,
            tableRef,
            false /* Dont delete intermediate */);

    queryBasedExport.prepare();
    queryBasedExport.beginExport();
    queryBasedExport.waitForUsableMapReduceInput();
    List<InputSplit> splits = exportToCloudStorage.getSplits(wrapper);

    splits.size();
    assertEquals(config.get(BigQueryConfiguration.TEMP_GCS_PATH_KEY),
        config.get("mapred.input.dir"));

    // Verify correct calls to BigQuery are made.
    verify(mockBigqueryJobs, times(2)).insert(any(String.class), any(Job.class));
    verify(mockBigqueryJobsInsert, times(2)).execute();
  }

  /**
   * Tests getSplits method of GsonBigQueryInputFormat when Bigquery connection error is thrown.
   */
  @Test
  public void testGetSplitsSecurityException()
      throws IOException, InterruptedException {
    when(mockBigquery.tables()).thenReturn(mockBigqueryTables);

    // Write values to file.
    ByteBuffer buffer = GsonRecordReaderTest.stringToBytebuffer(value1 + "\n" + value2 + "\n");
    Path mockPath = new Path("gs://test_bucket/path/test");
    GsonRecordReaderTest.writeFile(ghfs, mockPath, buffer);

    // Run getSplits method.
    GsonBigQueryInputFormat gsonBigQueryInputFormat =
        new GsonBigQueryInputFormatForTestGeneralSecurityException();
    config.set("mapred.input.dir", "gs://test_bucket/path/test");
    try {
      BigQueryJobWrapper wrapper = new BigQueryJobWrapper(config);
      wrapper.setJobID(new JobID());
      List<InputSplit> splits = gsonBigQueryInputFormat.getSplits(wrapper);
      fail("Expected IOException");
    } catch (IOException e) {
      // Expected.
    }
  }

  /**
   * Tests the cleanupJob method of GsonBigQueryInputFormat with intermediate delete.
   */
  @Test
  public void testCleanupJobWithIntermediateDeleteAndGcsDelete() 
      throws IOException {
    // Set intermediate table for deletion.
    config.setBoolean(BigQueryConfiguration.DELETE_INTERMEDIATE_TABLE_KEY, true);
    config.setBoolean(BigQueryConfiguration.DELETE_EXPORT_FILES_FROM_GCS_KEY, true);
    config.setBoolean(BigQueryConfiguration.ENABLE_SHARDED_EXPORT_KEY, true);

    // Mock method calls to delete temporary table.
    when(mockBigquery.tables()).thenReturn(mockBigqueryTables);
    when(mockBigqueryTables.delete(
        eq(dataProjectId), eq(intermediateDataset), eq(intermediateTable)))
        .thenReturn(mockBigqueryTablesDelete);

    Path tempPath = new Path(config.get(BigQueryConfiguration.TEMP_GCS_PATH_KEY));
    FileSystem fs = tempPath.getFileSystem(config);
    fs.mkdirs(tempPath);
    Path dataFile = new Path(tempPath.toString() + "/data-00000.json");
    fs.createNewFile(dataFile);

    // Check file and directory exist.
    assertTrue(fs.exists(tempPath));
    assertTrue(fs.exists(dataFile));

    // Run method and verify calls.
    GsonBigQueryInputFormat.cleanupJob(mockBigquery, config);
    assertTrue(!fs.exists(tempPath));
    assertTrue(!fs.exists(dataFile));

    // Verify calls to delete temporary table.
    verify(mockBigquery, atLeastOnce()).tables();
    verify(mockBigqueryTables)
        .delete(eq(dataProjectId), eq(intermediateDataset), eq(intermediateTable));
    verify(mockBigqueryTablesDelete).execute();
  }

  /**
   * Tests the cleanupJob method of GsonBigQueryInputFormat with intermediate delete.
   */
  @Test
  public void testCleanupJobWithIntermediateDeleteNoGcsDelete() 
      throws IOException {
    // Set intermediate table for deletion.
    config.setBoolean(BigQueryConfiguration.DELETE_INTERMEDIATE_TABLE_KEY, true);
    config.setBoolean(BigQueryConfiguration.DELETE_EXPORT_FILES_FROM_GCS_KEY, false);
    config.setBoolean(BigQueryConfiguration.ENABLE_SHARDED_EXPORT_KEY, true);

    // Mock method calls to delete temporary table.
    when(mockBigquery.tables()).thenReturn(mockBigqueryTables);
    when(mockBigqueryTables.delete(
        eq(dataProjectId), eq(intermediateDataset), eq(intermediateTable)))
        .thenReturn(mockBigqueryTablesDelete);

    Path tempPath = new Path(config.get(BigQueryConfiguration.TEMP_GCS_PATH_KEY));
    FileSystem fs = tempPath.getFileSystem(config);
    fs.mkdirs(tempPath);
    Path dataFile = new Path(tempPath.toString() + "/data-00000.json");
    fs.createNewFile(dataFile);

    // Check file and directory exist.
    assertTrue(fs.exists(tempPath));
    assertTrue(fs.exists(dataFile));

    // Run method and verify calls.
    GsonBigQueryInputFormat.cleanupJob(mockBigquery, config);
    assertTrue(fs.exists(tempPath));
    assertTrue(fs.exists(dataFile));

    // Verify calls to delete temporary table.
    verify(mockBigquery, times(2)).tables();
    verify(mockBigqueryTables)
        .delete(eq(dataProjectId), eq(intermediateDataset), eq(intermediateTable));
    verify(mockBigqueryTablesDelete).execute();
  }

  /**
   * Tests the cleanupJob method of GsonBigQueryInputFormat with no intermediate delete.
   */
  @Test
  public void testCleanupJobWithNoIntermediateDelete() 
      throws IOException {
    // Set intermediate table for deletion.
    config.setBoolean(BigQueryConfiguration.DELETE_INTERMEDIATE_TABLE_KEY, false);
    config.setBoolean(BigQueryConfiguration.DELETE_EXPORT_FILES_FROM_GCS_KEY, true);
    config.setBoolean(BigQueryConfiguration.ENABLE_SHARDED_EXPORT_KEY, true);

    Path tempPath = new Path(config.get(BigQueryConfiguration.TEMP_GCS_PATH_KEY));
    FileSystem fs = tempPath.getFileSystem(config);
    fs.mkdirs(tempPath);
    Path dataFile = new Path(tempPath.toString() + "/data-00000.json");
    fs.createNewFile(dataFile);
    assertTrue(fs.exists(tempPath));
    assertTrue(fs.exists(dataFile));

    // Run method and verify calls.
    GsonBigQueryInputFormat.cleanupJob(mockBigquery, config);

    assertTrue(!fs.exists(tempPath));
    assertTrue(!fs.exists(dataFile));

    verify(mockBigquery).tables();
    verify(mockBigqueryTables).get(
        eq(dataProjectId), eq(intermediateDataset), eq(intermediateTable));

    verifyNoMoreInteractions(mockBigquery, mockBigqueryTables);
  }
  
  /**
   * Tests the cleanupJob method of GsonBigQueryInputFormat with intermediate delete but no sharded
   * export.
   */
  @Test
  public void testCleanupJobWithIntermediateDeleteNoShardedExport() 
      throws IOException {
    // Set intermediate table for deletion.
    config.setBoolean(BigQueryConfiguration.DELETE_INTERMEDIATE_TABLE_KEY, true);
    config.setBoolean(BigQueryConfiguration.DELETE_EXPORT_FILES_FROM_GCS_KEY, true);
    config.setBoolean(BigQueryConfiguration.ENABLE_SHARDED_EXPORT_KEY, false);

    // GCS cleanup should still happen.
    Path tempPath = new Path(config.get(BigQueryConfiguration.TEMP_GCS_PATH_KEY));
    FileSystem fs = tempPath.getFileSystem(config);
    fs.mkdirs(tempPath);
    Path dataFile = new Path(tempPath.toString() + "/data-00000.json");
    fs.createNewFile(dataFile);
    assertTrue(fs.exists(tempPath));
    assertTrue(fs.exists(dataFile));

    when(mockBigquery.tables()).thenReturn(mockBigqueryTables);
    when(mockBigqueryTables
        .delete(
            eq(dataProjectId), eq(intermediateDataset), eq(intermediateTable)))
        .thenReturn(mockBigqueryTablesDelete);

    // Run method and verify calls.
    GsonBigQueryInputFormat.cleanupJob(mockBigquery, config);

    assertTrue(!fs.exists(tempPath));
    assertTrue(!fs.exists(dataFile));

    verify(mockBigquery).tables();
    verify(mockBigqueryTables).delete(
        eq(dataProjectId), eq(intermediateDataset), eq(intermediateTable));
    verify(mockBigqueryTablesDelete).execute();

    verifyNoMoreInteractions(mockBigquery);
  }

  /**
   * Helper class to provide a mock Bigquery for testing.
   */
  class GsonBigQueryInputFormatForTest
    extends GsonBigQueryInputFormat {
    @Override
    public Bigquery getBigQuery(Configuration config)
        throws GeneralSecurityException, IOException {
      return mockBigquery;
    }
  }

  /**
   * Helper class to test behavior when an error is thrown while getting the Bigquery connection.
   */
  class GsonBigQueryInputFormatForTestGeneralSecurityException
    extends GsonBigQueryInputFormat {
    @Override
    public Bigquery getBigQuery(Configuration config)
        throws GeneralSecurityException, IOException {
      throw new GeneralSecurityException();
    }
  }
}
