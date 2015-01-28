/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package co.cask.cdap.metrics.store;

import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.common.utils.ImmutablePair;
import co.cask.cdap.data2.dataset2.lib.table.FuzzyRowFilter;
import co.cask.cdap.data2.dataset2.lib.table.MetricsTable;
import co.cask.cdap.metrics.data.EntityTable;
import co.cask.cdap.metrics.transport.MetricType;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.NavigableMap;
import javax.annotation.Nullable;

/**
 * Table for storing time series metrics.
 */
// todo: not thread-safe!
public final class MetricTimeSeriesTable {
  private static final Logger LOG = LoggerFactory.getLogger(MetricTimeSeriesTable.class);
  private static final int MAX_ROLL_TIME = 0xfffe;

  private static final Function<byte[], Long> BYTES_TO_LONG = new Function<byte[], Long>() {
    @Override
    public Long apply(byte[] input) {
      return Bytes.toLong(input);
    }
  };

  private static final Function<NavigableMap<byte[], byte[]>, NavigableMap<byte[], Long>>
    TRANSFORM_MAP_BYTE_ARRAY_TO_LONG = new Function<NavigableMap<byte[], byte[]>, NavigableMap<byte[], Long>>() {
    @Override
    public NavigableMap<byte[], Long> apply(NavigableMap<byte[], byte[]> input) {
      return Maps.transformValues(input, BYTES_TO_LONG);
    }
  };

  private final MetricsTable timeSeriesTable;
  private final MetricRecordCodec codec;

  /**
   * Creates a MetricTable.
   *
   * @param timeSeriesTable A OVC table for storing metric information.
   * @param entityTable The {@link co.cask.cdap.metrics.data.MetricsEntityCodec} for encoding entity.
   * @param resolution Resolution in second of the table
   * @param rollTime Number of resolution for writing to a new row with a new timebase.
   *                 Meaning the differences between timebase of two consecutive rows divided by
   *                 resolution seconds. It essentially defines how many columns per row in the table.
   *                 This value should be < 65535.
   */
  MetricTimeSeriesTable(MetricsTable timeSeriesTable,
                        EntityTable entityTable, int resolution, int rollTime) {
    // Two bytes for column name, which is a delta timestamp
    Preconditions.checkArgument(rollTime <= MAX_ROLL_TIME, "Rolltime should be <= " + MAX_ROLL_TIME);

    this.timeSeriesTable = timeSeriesTable;
    this.codec = new MetricRecordCodec(entityTable, resolution, rollTime);
  }

  public void add(List<Aggregation> aggregations) throws Exception {
    // Simply collecting all rows/cols/values that need to be put to the underlying table.
    NavigableMap<byte[], NavigableMap<byte[], byte[]>> gaugesTable = Maps.newTreeMap(Bytes.BYTES_COMPARATOR);
    NavigableMap<byte[], NavigableMap<byte[], byte[]>> incrementsTable = Maps.newTreeMap(Bytes.BYTES_COMPARATOR);
    for (Aggregation agg : aggregations) {
      byte[] rowKey = codec.createRowKey(agg.getTagValues(), agg.getMetricName(), agg.getTimeValue().getTimestamp());
      byte[] column = codec.createColumn(agg.getTimeValue().getTimestamp());

      if (MetricType.COUNTER == agg.getMetricType()) {
        inc(incrementsTable, rowKey, column, agg.getTimeValue().getValue());
      } else {
        set(gaugesTable, rowKey, column, Bytes.toBytes(agg.getTimeValue().getValue()));
      }
    }

    NavigableMap<byte[], NavigableMap<byte[], Long>> convertedIncrementsTable =
      Maps.transformValues(incrementsTable, TRANSFORM_MAP_BYTE_ARRAY_TO_LONG);

    NavigableMap<byte[], NavigableMap<byte[], Long>> convertedGaugesTable =
      Maps.transformValues(gaugesTable, TRANSFORM_MAP_BYTE_ARRAY_TO_LONG);

    timeSeriesTable.put(convertedGaugesTable);
    timeSeriesTable.increment(convertedIncrementsTable);
  }

  public MetricScanner scan(MetricScan scan) throws Exception {
    byte[] startRow = codec.createRowKey(scan.getTagValues(), scan.getMetricName(), scan.getStartTs());
    byte[] endRow = codec.createRowKey(scan.getTagValues(), scan.getMetricName(), scan.getEndTs());
    endRow = Bytes.stopKeyForPrefix(endRow);

    // todo: if searching within same row, we can also provide start and end columns or list of columns

    FuzzyRowFilter fuzzyRowFilter = createFuzzyRowFilter(scan, startRow);

    return new MetricScanner(timeSeriesTable.scan(startRow, endRow, null, fuzzyRowFilter), codec,
                             scan.getStartTs(), scan.getEndTs());
  }

  @Nullable
  private FuzzyRowFilter createFuzzyRowFilter(MetricScan scan, byte[] startRow) {
    // if any of metric name or tag values are not provided, we do need fuzzy row filter, otherwise don't:
    // the scan is well defined by start & stop keys
    if (scan.getMetricName() != null) {
      boolean needFilter = false;
      for (TagValue tagValue : scan.getTagValues()) {
        if (tagValue.getValue() != null) {
          needFilter = true;
          break;
        }
      }
      if (!needFilter) {
        return null;
      }
    }

    byte[] fuzzyRowMask = codec.createFuzzyRowMask(scan.getTagValues(), scan.getMetricName());
    // note: we can use startRow, as it will contain all "fixed" parts of the key needed
    return new FuzzyRowFilter(ImmutableList.of(new ImmutablePair<byte[], byte[]>(startRow, fuzzyRowMask)));
  }

  /**
   * Clears the data. Note: keeps entities encodings.
   */
  public void deleteAllData() throws Exception {
    timeSeriesTable.deleteAll(new byte[]{});
  }

  // todo: shouldn't we aggregate "before" writing to MetricTimeSeriesTable? We could do it really efficient outside
  private static void inc(NavigableMap<byte[], NavigableMap<byte[], byte[]>> incrementsTable,
                   byte[] rowKey, byte[] column, long value) {
    byte[] oldValue = get(incrementsTable, rowKey, column);
    long newValue = value;
    if (oldValue != null) {
      if (Bytes.SIZEOF_LONG == oldValue.length) {
        newValue = Bytes.toLong(oldValue) + value;
      } else if (Bytes.SIZEOF_INT == oldValue.length) {
        // In 2.4 and older versions we stored it as int
        newValue = Bytes.toInt(oldValue) + value;
      } else {
        // should NEVER happen, unless the table is screwed up manually
        throw new IllegalStateException(
          String.format("Could not parse metric @row %s @column %s value %s as int or long",
                        Bytes.toStringBinary(rowKey), Bytes.toStringBinary(column), Bytes.toStringBinary(oldValue)));
      }

    }
    set(incrementsTable, rowKey, column, Bytes.toBytes(newValue));
  }

  private static byte[] get(NavigableMap<byte[], NavigableMap<byte[], byte[]>> table, byte[] row, byte[] column) {
    NavigableMap<byte[], byte[]> rowMap = table.get(row);
    return rowMap == null ? null : rowMap.get(column);
  }

  private static void set(NavigableMap<byte[], NavigableMap<byte[], byte[]>> table,
                          byte[] row, byte[] column, byte[] value) {
    NavigableMap<byte[], byte[]> rowMap = table.get(row);
    if (rowMap == null) {
      rowMap = Maps.newTreeMap(Bytes.BYTES_COMPARATOR);
      table.put(row, rowMap);
    }

    rowMap.put(column, value);
  }
}
