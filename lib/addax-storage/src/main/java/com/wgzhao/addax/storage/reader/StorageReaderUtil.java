/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  *   http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing,
 *  * software distributed under the License is distributed on an
 *  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  * KIND, either express or implied.  See the License for the
 *  * specific language governing permissions and limitations
 *  * under the License.
 *
 */

package com.wgzhao.addax.storage.reader;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.csvreader.CsvReader;
import com.wgzhao.addax.common.base.Constant;
import com.wgzhao.addax.common.base.Key;
import com.wgzhao.addax.common.compress.ZipCycleInputStream;
import com.wgzhao.addax.common.constant.Type;
import com.wgzhao.addax.common.element.BoolColumn;
import com.wgzhao.addax.common.element.Column;
import com.wgzhao.addax.common.element.ColumnEntry;
import com.wgzhao.addax.common.element.DateColumn;
import com.wgzhao.addax.common.element.DoubleColumn;
import com.wgzhao.addax.common.element.LongColumn;
import com.wgzhao.addax.common.element.Record;
import com.wgzhao.addax.common.element.StringColumn;
import com.wgzhao.addax.common.exception.AddaxException;
import com.wgzhao.addax.common.plugin.RecordSender;
import com.wgzhao.addax.common.plugin.TaskPluginCollector;
import com.wgzhao.addax.common.util.Configuration;
import io.airlift.compress.snappy.SnappyCodec;
import io.airlift.compress.snappy.SnappyFramedInputStream;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.UnsupportedCharsetException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class StorageReaderUtil
{
    private static final Logger LOG = LoggerFactory.getLogger(StorageReaderUtil.class);
    public static HashMap<String, Object> csvReaderConfigMap;

    private StorageReaderUtil()
    {

    }

    public static String[] splitBufferedReader(CsvReader csvReader)
            throws IOException
    {
        String[] splitedResult = null;
        if (csvReader.readRecord()) {
            splitedResult = csvReader.getValues();
        }
        return splitedResult;
    }

    public static void readFromStream(InputStream inputStream, String fileName,
            Configuration readerSliceConfig, RecordSender recordSender,
            TaskPluginCollector taskPluginCollector)
    {
        String compress = readerSliceConfig.getString(Key.COMPRESS, "");

        String encoding = readerSliceConfig.getString(Key.ENCODING, Constant.DEFAULT_ENCODING);
        // handle blank encoding
        if (StringUtils.isBlank(encoding)) {
            encoding = Constant.DEFAULT_ENCODING;
            LOG.warn("The encoding: '{}' is illegal, uses '{}' by default", encoding, Constant.DEFAULT_ENCODING);
        }

        List<Configuration> column = readerSliceConfig.getListConfiguration(Key.COLUMN);
        // handle ["*"] -> [], null
        if (null != column && 1 == column.size() && "\"*\"".equals(column.get(0).toString())) {
            readerSliceConfig.set(Key.COLUMN, null);
        }

        BufferedReader reader = null;
        int bufferSize = readerSliceConfig.getInt(Key.BUFFER_SIZE, Constant.DEFAULT_BUFFER_SIZE);

        // compress logic
        try {
            if (compress == null || "".equals(compress) ) {
                reader = new BufferedReader(new InputStreamReader(inputStream, encoding), bufferSize);
            }
            else {
                if ("hadoop-snappy".equalsIgnoreCase(compress)) {
                    CompressionCodec snappyCodec = new SnappyCodec();
                    InputStream snappyInputStream = snappyCodec.createInputStream(inputStream);
                    reader = new BufferedReader(new InputStreamReader(snappyInputStream, encoding));
                }
                else if ("framing-snappy".equalsIgnoreCase(compress)) {
                    InputStream snappyInputStream = new SnappyFramedInputStream(inputStream);
                    reader = new BufferedReader(new InputStreamReader(snappyInputStream, encoding));
                }
                else if ("zip".equalsIgnoreCase(compress)) {
                    ZipCycleInputStream zipCycleInputStream = new ZipCycleInputStream(inputStream);
                    reader = new BufferedReader(new InputStreamReader(zipCycleInputStream, encoding), bufferSize);
                }
                else {
                    // common-compress supports almost compress alg
                    CompressorInputStream input = new CompressorStreamFactory().createCompressorInputStream(compress, inputStream, true);
                    reader = new BufferedReader(new InputStreamReader(input, encoding), bufferSize);
                }
            }
            StorageReaderUtil.doReadFromStream(reader, fileName, readerSliceConfig, recordSender, taskPluginCollector);
        }
        catch (UnsupportedEncodingException uee) {
            throw AddaxException.asAddaxException(
                            StorageReaderErrorCode.OPEN_FILE_WITH_CHARSET_ERROR,
                            String.format("%s is unsupported", encoding), uee);
        }
        catch (NullPointerException e) {
            throw AddaxException.asAddaxException(
                    StorageReaderErrorCode.RUNTIME_EXCEPTION, e);
        }
        catch (IOException e) {
            throw AddaxException.asAddaxException(
                    StorageReaderErrorCode.READ_FILE_IO_ERROR, String.format("Read stream %s failure ", fileName), e);
        }
        catch (CompressorException e) {
            throw AddaxException.asAddaxException(
                    StorageReaderErrorCode.ILLEGAL_VALUE,
                    "The compress '" + compress + "' is supported"
            );
        }
        finally {
            IOUtils.closeQuietly(reader, null);
        }
    }

    public static void doReadFromStream(BufferedReader reader, String fileName,
            Configuration readerSliceConfig, RecordSender recordSender,
            TaskPluginCollector taskPluginCollector)
    {
        String encoding = readerSliceConfig.getString(Key.ENCODING, Constant.DEFAULT_ENCODING);
        Character fieldDelimiter;
        String delimiterInStr = readerSliceConfig
                .getString(Key.FIELD_DELIMITER);
        if (null != delimiterInStr && 1 != delimiterInStr.length()) {
            throw AddaxException.asAddaxException(
                    StorageReaderErrorCode.ILLEGAL_VALUE,
                    String.format("The delimiter ONLY has one char, '%s' is illegal", delimiterInStr));
        }
        if (null == delimiterInStr) {
            LOG.warn("Use {} as delimiter by default", Constant.DEFAULT_FIELD_DELIMITER);
        }

        // warn: default value ',', fieldDelimiter could be \n(lineDelimiter) for no fieldDelimiter
        fieldDelimiter = readerSliceConfig.getChar(Key.FIELD_DELIMITER, Constant.DEFAULT_FIELD_DELIMITER);
        Boolean skipHeader = readerSliceConfig.getBool(Key.SKIP_HEADER, Constant.DEFAULT_SKIP_HEADER);
        // warn: no default value '\N'
        String nullFormat = readerSliceConfig.getString(Key.NULL_FORMAT);

        List<ColumnEntry> column = StorageReaderUtil.getListColumnEntry(readerSliceConfig, Key.COLUMN);
        CsvReader csvReader = null;

        // every line logic
        try {
            // TODO lineDelimiter
            if (skipHeader) {
                String fetchLine = reader.readLine();
                LOG.info("Header line {} has been skipped.",
                        fetchLine);
            }
            csvReader = new CsvReader(reader);
            csvReader.setDelimiter(fieldDelimiter);

            setCsvReaderConfig(csvReader);

            String[] parseRows;
            while ((parseRows = StorageReaderUtil.splitBufferedReader(csvReader)) != null) {
                StorageReaderUtil.transportOneRecord(recordSender, column, parseRows, nullFormat, taskPluginCollector);
            }
        }
        catch (UnsupportedEncodingException uee) {
            throw AddaxException.asAddaxException(
                    StorageReaderErrorCode.OPEN_FILE_WITH_CHARSET_ERROR,
                    String.format("encoding: '%s' is unsupported", encoding), uee);
        }
        catch (FileNotFoundException fnfe) {
            throw AddaxException.asAddaxException(
                    StorageReaderErrorCode.FILE_NOT_EXISTS, String.format("The file '%s' does not exists ", fileName), fnfe);
        }
        catch (IOException ioe) {
            throw AddaxException.asAddaxException(
                    StorageReaderErrorCode.READ_FILE_IO_ERROR, String.format("Read file '%s' failure ", fileName), ioe);
        }
        catch (Exception e) {
            throw AddaxException.asAddaxException(
                    StorageReaderErrorCode.RUNTIME_EXCEPTION, e);
        }
        finally {
            if (csvReader != null) {
                csvReader.close();
            }
            IOUtils.closeQuietly(reader, null);
        }
    }

    public static void transportOneRecord(RecordSender recordSender, Configuration configuration,
            TaskPluginCollector taskPluginCollector, String line)
    {
        List<ColumnEntry> column = StorageReaderUtil.getListColumnEntry(configuration, Key.COLUMN);
        // 注意: nullFormat 没有默认值
        String nullFormat = configuration.getString(Key.NULL_FORMAT);

        // warn: default value ',', fieldDelimiter could be \n(lineDelimiter)
        // for no fieldDelimiter
        Character fieldDelimiter = configuration.getChar(Key.FIELD_DELIMITER, Constant.DEFAULT_FIELD_DELIMITER);

        String[] sourceLine = StringUtils.split(line, fieldDelimiter);

        transportOneRecord(recordSender, column, sourceLine, nullFormat, taskPluginCollector);
    }

    public static void transportOneRecord(RecordSender recordSender, List<ColumnEntry> columnConfigs, String[] sourceLine,
            String nullFormat, TaskPluginCollector taskPluginCollector)
    {
        Record record = recordSender.createRecord();
        Column columnGenerated;

        // 创建都为String类型column的record
        if (null == columnConfigs || columnConfigs.isEmpty()) {
            for (String columnValue : sourceLine) {
                // not equalsIgnoreCase, it's all ok if nullFormat is null
                if (columnValue.equals(nullFormat)) {
                    columnGenerated = new StringColumn(null);
                }
                else {
                    columnGenerated = new StringColumn(columnValue);
                }
                record.addColumn(columnGenerated);
            }
            recordSender.sendToWriter(record);
        }
        else {
            try {
                for (ColumnEntry columnConfig : columnConfigs) {
                    String columnType = columnConfig.getType();
                    Integer columnIndex = columnConfig.getIndex();
                    String columnConst = columnConfig.getValue();

                    String columnValue;

                    if (null == columnIndex && null == columnConst) {
                        throw AddaxException.asAddaxException(
                                StorageReaderErrorCode.NO_INDEX_VALUE, "The index or type is required when type is present.");
                    }

                    if (null != columnIndex && null != columnConst) {
                        throw AddaxException.asAddaxException(
                                StorageReaderErrorCode.MIXED_INDEX_VALUE, "The index and value are both present, choose one of them");
                    }

                    if (null != columnIndex) {
                        if (columnIndex >= sourceLine.length) {
                            String message = String.format("The column index %s you try to read is out of range(%s): %s",
                                    columnIndex + 1, sourceLine.length,  StringUtils.join(sourceLine, ","));
                            LOG.warn(message);
                            throw new IndexOutOfBoundsException(message);
                        }
                        columnValue = sourceLine[columnIndex];
                    }
                    else {
                        columnValue = columnConst;
                    }
                    Type type = Type.valueOf(columnType.toUpperCase());
                    // it's all ok if nullFormat is null
                    if (columnValue == null || columnValue.equals(nullFormat)) {
                        record.addColumn(new StringColumn());
                        continue;
                    }

                    String errorTemplate = "Cast %s to %s failure";
                    switch (type) {
                        case STRING:
                            columnGenerated = new StringColumn(columnValue);
                            break;
                        case LONG:
                            try {
                                columnGenerated = new LongColumn(columnValue);
                            }
                            catch (Exception e) {
                                throw new IllegalArgumentException(String.format(errorTemplate, columnValue, "LONG"));
                            }
                            break;
                        case DOUBLE:
                            try {
                                columnGenerated = new DoubleColumn(columnValue);
                            }
                            catch (Exception e) {
                                throw new IllegalArgumentException(String.format(errorTemplate, columnValue, "DOUBLE"));
                            }
                            break;
                        case BOOLEAN:
                            try {
                                columnGenerated = new BoolColumn(columnValue);
                            }
                            catch (Exception e) {
                                throw new IllegalArgumentException(String.format(errorTemplate, columnValue, "BOOLEAN"));
                            }
                            break;
                        case DATE:
                            try {
                                String formatString = columnConfig.getFormat();
                                if (StringUtils.isNotBlank(formatString)) {
                                    // 用户自己配置的格式转换, 脏数据行为出现变化
                                    DateFormat format = columnConfig.getDateFormat();
                                    columnGenerated = new DateColumn(format.parse(columnValue));
                                }
                                else {
                                    // 框架尝试转换
                                    columnGenerated = new DateColumn(new StringColumn(columnValue).asDate());
                                }
                            }
                            catch (Exception e) {
                                throw new IllegalArgumentException(String.format(errorTemplate, columnValue, "DATE"));
                            }
                            break;
                        default:
                            String errorMessage = String.format("The column type '%s' is unsupported", columnType);
                            LOG.error(errorMessage);
                            throw AddaxException.asAddaxException(StorageReaderErrorCode.NOT_SUPPORT_TYPE, errorMessage);
                    }

                    record.addColumn(columnGenerated);
                }
                recordSender.sendToWriter(record);
            }
            catch (IllegalArgumentException | IndexOutOfBoundsException iae) {
                LOG.error(iae.getMessage());
                taskPluginCollector.collectDirtyRecord(record, iae.getMessage());
            }
            catch (Exception e) {
                if (e instanceof AddaxException) {
                    throw (AddaxException) e;
                }
                // 每一种转换失败都是脏数据处理,包括数字格式 & 日期格式
                taskPluginCollector.collectDirtyRecord(record, e.getMessage());
            }
        }
    }

    public static List<ColumnEntry> getListColumnEntry(Configuration configuration, final String path)
    {
        List<JSONObject> lists = configuration.getList(path, JSONObject.class);
        if (lists == null) {
            return null;
        }
        List<ColumnEntry> result = new ArrayList<>();
        for (final JSONObject object : lists) {
            result.add(JSON.parseObject(object.toJSONString(), ColumnEntry.class));
        }
        return result;
    }

    /**
     * check parameter:encoding, compress, filedDelimiter
     *
     * @param readerConfiguration 配置项
     */
    public static void validateParameter(Configuration readerConfiguration)
    {

        // encoding check
        validateEncoding(readerConfiguration);

        //only support compress types
        validateCompress(readerConfiguration);

        //fieldDelimiter check
        validateFieldDelimiter(readerConfiguration);

        // column: 1. index type 2.value type 3.when type is Date, may have format
        validateColumn(readerConfiguration);
    }

    public static void validateEncoding(Configuration readerConfiguration)
    {
        // encoding check
        String encoding = readerConfiguration.getString(Key.ENCODING, Constant.DEFAULT_ENCODING);
        try {
            encoding = encoding.trim();
            readerConfiguration.set(Key.ENCODING, encoding);
            Charsets.toCharset(encoding);
        }
        catch (UnsupportedCharsetException uce) {
            throw AddaxException.asAddaxException(StorageReaderErrorCode.ILLEGAL_VALUE,
                    String.format("不支持您配置的编码格式 : [%s]", encoding), uce);
        }
        catch (Exception e) {
            throw AddaxException.asAddaxException(StorageReaderErrorCode.CONFIG_INVALID_EXCEPTION,
                    String.format("编码配置异常, 请联系我们: %s", e.getMessage()), e);
        }
    }

    public static void validateCompress(Configuration readerConfiguration)
    {
        String compress = readerConfiguration.getUnnecessaryValue(Key.COMPRESS, "").toLowerCase();
        if ("gzip".equals(compress)) {
            compress = "gz";
        }
        readerConfiguration.set(Key.COMPRESS, compress);
    }

    public static void validateFieldDelimiter(Configuration readerConfiguration)
    {
        //fieldDelimiter check
        String delimiterInStr = readerConfiguration.getString(Key.FIELD_DELIMITER, ",");
        if (null == delimiterInStr) {
            throw AddaxException.asAddaxException(StorageReaderErrorCode.REQUIRED_VALUE,
                    String.format("您提供配置文件有误，[%s]是必填参数.",
                            Key.FIELD_DELIMITER));
        }
        else if (1 != delimiterInStr.length()) {
            // warn: if have, length must be one
            throw AddaxException.asAddaxException(StorageReaderErrorCode.ILLEGAL_VALUE,
                    String.format("仅仅支持单字符切分, 您配置的切分为 : [%s]", delimiterInStr));
        }
    }

    public static void validateColumn(Configuration readerConfiguration)
    {
        // column: 1. index type 2.value type 3.when type is Date, may have
        // format
        List<Configuration> columns = readerConfiguration.getListConfiguration(Key.COLUMN);
        if (null == columns || columns.isEmpty()) {
            throw AddaxException.asAddaxException(StorageReaderErrorCode.REQUIRED_VALUE, "您需要指定 columns");
        }
        // handle ["*"]
        if (1 == columns.size()) {
            String columnsInStr = columns.get(0).toString();
            if ("\"*\"".equals(columnsInStr) || "'*'".equals(columnsInStr)) {
                readerConfiguration.set(Key.COLUMN, null);
                columns = null;
            }
        }

        if (null != columns && !columns.isEmpty()) {
            for (Configuration eachColumnConf : columns) {
                eachColumnConf.getNecessaryValue(Key.TYPE, StorageReaderErrorCode.REQUIRED_VALUE);
                Integer columnIndex = eachColumnConf.getInt(Key.INDEX);
                String columnValue = eachColumnConf.getString(Key.VALUE);

                if (null == columnIndex && null == columnValue) {
                    throw AddaxException.asAddaxException(StorageReaderErrorCode.NO_INDEX_VALUE,
                            "You must configure one of index or name or value");
                }

                if (null != columnIndex && null != columnValue) {
                    throw AddaxException.asAddaxException(StorageReaderErrorCode.MIXED_INDEX_VALUE,
                            "You both configure index, value, or name, you can ONLY specify the one each column");
                }
                if (null != columnIndex && columnIndex < 0) {
                    throw AddaxException.asAddaxException(StorageReaderErrorCode.ILLEGAL_VALUE,
                            String.format("The value of index must be greater than 0, %s is illegal", columnIndex));
                }
            }
        }
    }

    public static void validateCsvReaderConfig(Configuration readerConfiguration)
    {
        String csvReaderConfig = readerConfiguration.getString(Key.CSV_READER_CONFIG);
        if (StringUtils.isNotBlank(csvReaderConfig)) {
            try {
                StorageReaderUtil.csvReaderConfigMap = JSON.parseObject(csvReaderConfig, new TypeReference<HashMap<String, Object>>() {});
            }
            catch (Exception e) {
                LOG.info("WARN!!!!忽略csvReaderConfig配置! 配置错误,值只能为空或者为Map结构,您配置的值为: {}", csvReaderConfig);
            }
        }
    }

    /**
     * 获取含有通配符路径的父目录，目前只支持在最后一级目录使用通配符*或者
     *
     * @param regexPath path
     * @return String
     */
    public static String getRegexPathParentPath(String regexPath)
    {
        int lastDirSeparator = regexPath.lastIndexOf(IOUtils.DIR_SEPARATOR);
        String parentPath;
        parentPath = regexPath.substring(0, lastDirSeparator + 1);
        if (parentPath.contains("*") || parentPath.contains("?")) {
            throw AddaxException.asAddaxException(StorageReaderErrorCode.ILLEGAL_VALUE,
                    String.format("The path '%s' is illegal, ONLY the trail folder can container wildcard * or ? ",
                            regexPath));
        }
        return parentPath;
    }

    public static void setCsvReaderConfig(CsvReader csvReader)
    {
        if (null != StorageReaderUtil.csvReaderConfigMap && !StorageReaderUtil.csvReaderConfigMap.isEmpty()) {
            try {
                BeanUtils.populate(csvReader, StorageReaderUtil.csvReaderConfigMap);
                LOG.info("csvReaderConfig has configured, current CsvReader: {}", JSON.toJSONString(csvReader));
            }
            catch (Exception e) {
                LOG.warn("The configure item [{}] is illegal, use default CsvReader [{}]",
                        JSON.toJSONString(StorageReaderUtil.csvReaderConfigMap), JSON.toJSONString(csvReader));
            }
        }
        else {
            //默认关闭安全模式, 放开10W字节的限制
            csvReader.setSafetySwitch(false);
            LOG.info("The configure item [{}] is illegal, use default CsvReader [{}]",
                    JSON.toJSONString(csvReader), JSON.toJSONString(StorageReaderUtil.csvReaderConfigMap));
        }
    }
}
