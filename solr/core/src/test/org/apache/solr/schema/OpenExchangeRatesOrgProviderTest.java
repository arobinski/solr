/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.schema;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.util.ResourceLoader;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.SuppressForbidden;
import org.apache.solr.core.SolrResourceLoader;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests currency field type.
 */
public class OpenExchangeRatesOrgProviderTest extends SolrTestCaseJ4 {
  OpenExchangeRatesOrgProvider oerp;
  ResourceLoader loader;
  private final Map<String,String> mockParams = new HashMap<>();


  @Override
  @Before
  public void setUp() throws Exception {
    CurrencyFieldTypeTest.assumeCurrencySupport
      ("USD", "EUR", "MXN", "GBP", "JPY");

    super.setUp();
    mockParams.put(OpenExchangeRatesOrgProvider.PARAM_RATES_FILE_LOCATION, 
                   "open-exchange-rates.json");  
    oerp = new OpenExchangeRatesOrgProvider();
    loader = new SolrResourceLoader(TEST_PATH().resolve("collection1"));
  }
  
  @Test
  public void testInit() throws Exception {
    oerp.init(mockParams);
    // don't inform, we don't want to hit any of these URLs

    assertEquals("Wrong url", 
                 "open-exchange-rates.json", oerp.ratesFileLocation);
    assertEquals("Wrong default interval", (1440*60), oerp.refreshIntervalSeconds);

    Map<String,String> params = new HashMap<>();
    params.put(OpenExchangeRatesOrgProvider.PARAM_RATES_FILE_LOCATION, 
               "http://foo.bar/baz");
    params.put(OpenExchangeRatesOrgProvider.PARAM_REFRESH_INTERVAL, "100");

    oerp.init(params);
    assertEquals("Wrong param set url", 
                 "http://foo.bar/baz", oerp.ratesFileLocation);
    assertEquals("Wrong param interval", (100*60), oerp.refreshIntervalSeconds);

  }

  @Test
  public void testList() {
    oerp.init(mockParams);
    oerp.inform(loader);
    assertEquals(5, oerp.listAvailableCurrencies().size());
  }

  @Test
  public void testGetExchangeRate() {
    oerp.init(mockParams);
    oerp.inform(loader);
    assertEquals(81.29D, oerp.getExchangeRate("USD", "JPY"), 0.0D);    
    assertEquals("USD", oerp.rates.getBaseCurrency());
  }

  @SuppressForbidden(reason = "Needs currentTimeMillis to check if reload happens")
  @Test
  public void testReload() {
    // reminder: interval is in minutes
    mockParams.put(OpenExchangeRatesOrgProvider.PARAM_REFRESH_INTERVAL, "100");
    oerp.init(mockParams);
    oerp.inform(loader);

    // modify the timestamp to be "current", then fetch a rate and ensure no reload
    // (subtract one minute to be sure that it didn't change; if it was precisely the current timestamp,
    // it could stay the same even when reload happened because it takes less than a second to reload)
    final long currentTimestamp = System.currentTimeMillis() / 1000 - 60;
    oerp.rates.setTimestamp(currentTimestamp);
    assertEquals(81.29D, oerp.getExchangeRate("USD", "JPY"), 0.0D);    
    assertEquals(currentTimestamp, oerp.rates.getTimestamp());

    // roll back clock on timestamp and ensure rate fetch does reload
    long timestampBeforeTheRefreshInterval = currentTimestamp - (101 * 60);
    oerp.rates.setTimestamp(timestampBeforeTheRefreshInterval);
    assertEquals(81.29D, oerp.getExchangeRate("USD", "JPY"), 0.0D);
    assertTrue(
            "timestamp is not greater than before, indicating no reload",
            oerp.rates.getTimestamp() > timestampBeforeTheRefreshInterval
    );
  }

  @SuppressForbidden(reason = "Needs currentTimeMillis to check if reload happens")
  @Test
  public void testNoReloadWhenParameterIsFalse() {
    mockParams.put(OpenExchangeRatesOrgProvider.PARAM_REFRESH_INTERVAL, "100");
    mockParams.put(OpenExchangeRatesOrgProvider.PARAM_REFRESH_WHILE_SEARCHING, "false");
    oerp.init(mockParams);
    oerp.inform(loader);

    long timestampBeforeTheRefreshInterval = System.currentTimeMillis() / 1000 - (101 * 60);
    oerp.rates.setTimestamp(timestampBeforeTheRefreshInterval);
    assertEquals(81.29D, oerp.getExchangeRate("USD", "JPY"), 0.0D);
    assertEquals(timestampBeforeTheRefreshInterval, oerp.rates.getTimestamp());
  }

  @Test(expected=SolrException.class)
  public void testNoInit() {
    oerp.getExchangeRate("ABC", "DEF");
    assertTrue("Should have thrown exception if not initialized", false);
  }
}
