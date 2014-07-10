package com.continuuity.data2.dataset2.lib.table.hbase;

import com.continuuity.api.common.Bytes;
import com.continuuity.api.dataset.DatasetProperties;
import com.continuuity.api.dataset.DatasetSpecification;
import com.continuuity.api.dataset.table.ConflictDetection;
import com.continuuity.common.conf.CConfiguration;
import com.continuuity.data.hbase.HBaseTestBase;
import com.continuuity.data.hbase.HBaseTestFactory;
import com.continuuity.data2.dataset.lib.table.BufferingOcTableClient;
import com.continuuity.data2.dataset.lib.table.hbase.HBaseOcTableClient;
import com.continuuity.data2.dataset2.lib.table.BufferingOrederedTableTest;
import com.continuuity.data2.transaction.Transaction;
import com.continuuity.data2.transaction.TxConstants;
import com.continuuity.data2.transaction.inmemory.DetachedTxSystemClient;
import com.continuuity.data2.util.hbase.HBaseTableUtil;
import com.continuuity.data2.util.hbase.HBaseTableUtilFactory;
import com.continuuity.test.SlowTests;
import com.google.gson.Gson;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.twill.filesystem.LocalLocationFactory;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 *
 */
@Category(SlowTests.class)
public class HBaseOrderedTableTest extends BufferingOrederedTableTest<BufferingOcTableClient> {
  private static final Logger LOG = LoggerFactory.getLogger(HBaseOrderedTableTest.class);

  @ClassRule
  public static TemporaryFolder tmpFolder = new TemporaryFolder();

  private static HBaseTestBase testHBase;
  private static HBaseTableUtil hBaseTableUtil = new HBaseTableUtilFactory().get();

  @BeforeClass
  public static void beforeClass() throws Exception {
    testHBase = new HBaseTestFactory().get();
    testHBase.startHBase();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    testHBase.stopHBase();
  }

  @Override
  protected BufferingOcTableClient getTable(String name, ConflictDetection conflictLevel) throws Exception {
    // ttl=-1 means "keep data forever"
    return
      new HBaseOcTableClient(name,
                             com.continuuity.data2.dataset.lib.table.ConflictDetection.valueOf(conflictLevel.name()),
                             -1, testHBase.getConfiguration());
  }

  @Override
  protected HBaseOrderedTableAdmin getTableAdmin(String name) throws Exception {
    return getAdmin(name, DatasetProperties.EMPTY);
  }

  private HBaseOrderedTableAdmin getAdmin(String name, DatasetProperties props) throws IOException {
    DatasetSpecification spec = new HBaseOrderedTableDefinition("foo").configure(name, props);
    return new HBaseOrderedTableAdmin(spec, testHBase.getConfiguration(), hBaseTableUtil,
                                      CConfiguration.create(), new LocalLocationFactory(tmpFolder.newFolder()));
  }

  @Test
  public void testTTL() throws Exception {
    // for the purpose of this test it is fine not to configure ttl when creating table: we want to see if it
    // applies on reading
    int ttl = 1000;
    DatasetProperties props = DatasetProperties.builder().add(TxConstants.PROPERTY_TTL, String.valueOf(ttl)).build();
    getAdmin("ttl", props).create();
    HBaseOcTableClient table = new HBaseOcTableClient("ttl",
                                                      com.continuuity.data2.dataset.lib.table.ConflictDetection.ROW,
                                                      ttl, testHBase.getConfiguration());

    DetachedTxSystemClient txSystemClient = new DetachedTxSystemClient();
    Transaction tx = txSystemClient.startShort();
    table.startTx(tx);
    table.put(b("row1"), b("col1"), b("val1"));
    table.commitTx();

    TimeUnit.SECONDS.sleep(2);

    tx = txSystemClient.startShort();
    table.startTx(tx);
    table.put(b("row2"), b("col2"), b("val2"));
    table.commitTx();

    // now, we should not see first as it should have expired, but see the last one
    tx = txSystemClient.startShort();
    table.startTx(tx);
    byte[] val = table.get(b("row1"), b("col1"));
    if (val != null) {
      LOG.info("Unexpected value " + Bytes.toStringBinary(val));
    }
    Assert.assertNull(val);
    Assert.assertArrayEquals(b("val2"), table.get(b("row2"), b("col2")));

    // test a table with no TTL
    DatasetProperties props2 = DatasetProperties.builder().add(TxConstants.PROPERTY_TTL, String.valueOf(-1)).build();
    getAdmin("nottl", props2).create();
    HBaseOcTableClient table2 = new HBaseOcTableClient("nottl",
                                                     com.continuuity.data2.dataset.lib.table.ConflictDetection.ROW, -1,
                                                     testHBase.getConfiguration());

    tx = txSystemClient.startShort();
    table2.startTx(tx);
    table2.put(b("row1"), b("col1"), b("val1"));
    table2.commitTx();

    TimeUnit.SECONDS.sleep(2);

    tx = txSystemClient.startShort();
    table2.startTx(tx);
    table2.put(b("row2"), b("col2"), b("val2"));
    table2.commitTx();

    // if ttl is -1 (unlimited), it should see both
    tx = txSystemClient.startShort();
    table2.startTx(tx);
    Assert.assertArrayEquals(b("val1"), table2.get(b("row1"), b("col1")));
    Assert.assertArrayEquals(b("val2"), table2.get(b("row2"), b("col2")));
  }

  @Test
  public void testPreSplit() throws Exception {
    byte[][] splits = new byte[][] {Bytes.toBytes("a"), Bytes.toBytes("b"), Bytes.toBytes("c")};
    DatasetProperties props = DatasetProperties.builder().add("hbase.splits", new Gson().toJson(splits)).build();
    getAdmin("presplitted", props).create();

    HBaseAdmin hBaseAdmin = testHBase.getHBaseAdmin();
    try {
      List<HRegionInfo> regions = hBaseAdmin.getTableRegions(Bytes.toBytes("presplitted"));
      // note: first region starts at very first row key, so we have one extra to the splits count
      Assert.assertEquals(4, regions.size());
      Assert.assertArrayEquals(Bytes.toBytes("a"), regions.get(1).getStartKey());
      Assert.assertArrayEquals(Bytes.toBytes("b"), regions.get(2).getStartKey());
      Assert.assertArrayEquals(Bytes.toBytes("c"), regions.get(3).getStartKey());
    } finally {
      hBaseAdmin.close();
    }
  }

  private static byte[] b(String s) {
    return Bytes.toBytes(s);
  }

}
