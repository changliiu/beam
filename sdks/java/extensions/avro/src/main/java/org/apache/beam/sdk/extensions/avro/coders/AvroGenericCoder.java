/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.avro.coders;

import java.util.concurrent.ExecutionException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.extensions.avro.io.AvroDatumFactory;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.cache.Cache;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.cache.CacheBuilder;

/** AvroCoder specialisation for GenericRecord, needed for cross-language transforms. */
public class AvroGenericCoder extends AvroCoder<GenericRecord> {
  private static final Cache<Schema, AvroGenericCoder> AVRO_GENERIC_CODER_CACHE =
      CacheBuilder.newBuilder().weakValues().build();

  AvroGenericCoder(Schema schema) {
    super(AvroDatumFactory.GenericDatumFactory.INSTANCE, schema);
  }

  public static AvroGenericCoder of(Schema schema) {
    try {
      return AVRO_GENERIC_CODER_CACHE.get(schema, () -> new AvroGenericCoder(schema));
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }
}
