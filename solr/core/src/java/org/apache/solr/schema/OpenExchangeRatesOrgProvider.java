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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.ObjectReleaseTracker;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.noggit.JSONParser;
import org.apache.lucene.util.ResourceLoader;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * Exchange Rates Provider for {@link CurrencyField} and {@link CurrencyFieldType} capable of fetching &amp;
 * parsing the freely available exchange rates from openexchangerates.org
 * </p>
 * <p>
 * Configuration Options:
 * </p>
 * <ul>
 *  <li><code>ratesFileLocation</code> - A file path or absolute URL specifying the JSON data to load (mandatory)</li>
 *  <li><code>refreshInterval</code> - How frequently (in minutes) to reload the exchange rate data (default: 1440)</li>
 * </ul>
 * <p>
 * <b>Disclaimer:</b> This data is collected from various providers and provided free of charge
 * for informational purposes only, with no guarantee whatsoever of accuracy, validity,
 * availability or fitness for any purpose; use at your own risk. Other than that - have
 * fun, and please share/watch/fork if you think data like this should be free!
 * </p>
 * @see <a href="https://openexchangerates.org/documentation">openexchangerates.org JSON Data Format</a>
 */
public class OpenExchangeRatesOrgProvider implements ExchangeRateProvider {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  protected static final String PARAM_RATES_FILE_LOCATION   = "ratesFileLocation";
  protected static final String PARAM_REFRESH_INTERVAL      = "refreshInterval";
  protected static final String DEFAULT_REFRESH_INTERVAL    = "1440";

  protected String ratesFileLocation;
  // configured in minutes, but stored in seconds for quicker math
  protected int refreshIntervalSeconds;
  protected ResourceLoader resourceLoader;
  private final ExecutorService executorService = ExecutorUtil.newMDCAwareSingleThreadExecutor(
      new SolrNamedThreadFactory("currencyProvider"));

  private volatile boolean reloading = false;
  private volatile long lastReloadTimestamp;

  protected OpenExchangeRates rates;

  public OpenExchangeRatesOrgProvider() {
    log.debug("Adding ExecutorService to be tracked by ObjectReleaseTracker");
    assert ObjectReleaseTracker.track(executorService); // ensure that in unclean shutdown tests we still close this
  }

  /**
   * Returns the currently known exchange rate between two currencies. The rates are fetched from
   * the freely available OpenExchangeRates.org JSON, hourly updated. All rates are symmetrical with
   * base currency being USD by default.
   *
   * @param sourceCurrencyCode The source currency being converted from.
   * @param targetCurrencyCode The target currency being converted to.
   * @return The exchange rate.
   * @throws SolrException if the requested currency pair cannot be found
   */
  @Override
  public double getExchangeRate(String sourceCurrencyCode, String targetCurrencyCode) {
    log.debug("getExchangeRate called");
    if (rates == null) {
      throw new SolrException(SolrException.ErrorCode.SERVICE_UNAVAILABLE, "Rates not initialized.");
    }

    if (sourceCurrencyCode == null || targetCurrencyCode == null) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Cannot get exchange rate; currency was null.");
    }

    OpenExchangeRates currentRates = rates;

    reload();

    Double source = currentRates.getRates().get(sourceCurrencyCode);
    Double target = currentRates.getRates().get(targetCurrencyCode);

    if (source == null || target == null) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
          "No available conversion rate from " + sourceCurrencyCode + " to " + targetCurrencyCode + ". "
          + "Available rates are "+listAvailableCurrencies());
    }

    return target / source;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    OpenExchangeRatesOrgProvider that = (OpenExchangeRatesOrgProvider) o;

    return !(rates != null ? !rates.equals(that.rates) : that.rates != null);
  }

  @Override
  public int hashCode() {
    return rates != null ? rates.hashCode() : 0;
  }

  @Override
  public String toString() {
    return "["+this.getClass().getName()+" : " + rates.getRates().size() + " rates.]";
  }

  @Override
  public Set<String> listAvailableCurrencies() {
    if (rates == null)
      throw new SolrException(ErrorCode.SERVER_ERROR, "Rates not initialized");
    return rates.getRates().keySet();
  }

  public void reloadNow() throws SolrException {
    try {
      log.debug("Reloading exchange rates from {}", ratesFileLocation);

      // We set the timestamp based on the monotonic time not the time from openexchangerates.com because
      // in the reload() method we will be comparing it to the monotonic time. If we took the time
      // from openexchangerates.com and the timestamp was off or the system clock was set to a different time, we could
      // be refreshing the exchange rates too often (even on every search request) or too rarely. Also, it's necessary
      // to set the timestamp to the current time and to do it before the actual reload, so in the case
      // when the openexchangerates.com server is down for more than 60 minutes, we don't try to refresh the rates
      // on every search request.
      lastReloadTimestamp = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime());

      rates = new OpenExchangeRates();
      log.debug("Successfully reloaded exchange rates from {}", ratesFileLocation);
    } catch (Exception e) {
      log.error("Error reloading exchange rates", e);
    } finally {
      reloading = false;
    }
  }

  @Override
  public boolean reload() throws SolrException {
    if ((lastReloadTimestamp + refreshIntervalSeconds) >= TimeUnit.NANOSECONDS.toSeconds(System.nanoTime())) {
      return true;
    }

    synchronized (this) {
      if (!reloading) {
        log.debug("Refresh interval has expired. Refreshing exchange rates (in a separate thread).");
        reloading = true;
        executorService.submit(this::reloadNow);
      }
    }
    return true;
  }

  public static void shutdown() {
    //TODO: this is never called. Find a place from where we can call executorService.shutdown();
    log.debug("shutdown called on OpenExchangeRatesOrgProvider");
  }

  @Override
  public void init(Map<String,String> params) throws SolrException {
    try {
      ratesFileLocation = params.get(PARAM_RATES_FILE_LOCATION);
      if (null == ratesFileLocation) {
        throw new SolrException(ErrorCode.SERVER_ERROR, "Init param must be specified: " + PARAM_RATES_FILE_LOCATION);
      }
      int refreshInterval = Integer.parseInt(getParam(params.get(PARAM_REFRESH_INTERVAL), DEFAULT_REFRESH_INTERVAL));
      // Force a refresh interval of minimum one hour, since the API does not offer better resolution
      if (refreshInterval < 60) {
        refreshInterval = 60;
        log.warn("Specified refreshInterval was too small. Setting to 60 minutes which is the update rate of openexchangerates.org");
      }
      log.debug("Initialized with rates={}, refreshInterval={}.", ratesFileLocation, refreshInterval);
      refreshIntervalSeconds = refreshInterval * 60;
    } catch (SolrException e1) {
      throw e1;
    } catch (Exception e2) {
      throw new SolrException(ErrorCode.SERVER_ERROR, "Error initializing: " +
                              e2.getMessage(), e2);
    } finally {
      // Removing config params custom to us
      params.remove(PARAM_RATES_FILE_LOCATION);
      params.remove(PARAM_REFRESH_INTERVAL);
    }
  }

  @Override
  public void inform(ResourceLoader loader) throws SolrException {
    log.debug("inform with ResourceLoader called");
    resourceLoader = loader;
    reloadNow();
  }

  private String getParam(String param, String defaultParam) {
    return param == null ? defaultParam : param;
  }

  /**
   * A simple class encapsulating the JSON data from openexchangerates.org
   */
  class OpenExchangeRates {
    private Map<String, Double> rates;
    private String baseCurrency;
    private String disclaimer;
    private String license;

    public OpenExchangeRates() throws IOException {
      InputStream ratesJsonStream;
      try {
        ratesJsonStream = (new URL(ratesFileLocation)).openStream();
      } catch (Exception e) {
        ratesJsonStream = resourceLoader.openResource(ratesFileLocation);
      }

      try {
        parse(ratesJsonStream);
      }
      finally {
        ratesJsonStream.close();
      }
    }

    private void parse(InputStream ratesJsonStream) throws IOException {
      JSONParser parser = new JSONParser(new InputStreamReader(ratesJsonStream, StandardCharsets.UTF_8));
      rates = new HashMap<>();

      int ev;
      do {
        ev = parser.nextEvent();
        switch( ev ) {
          case JSONParser.STRING:
            if (parser.wasKey()) {
              String key = parser.getString();
              if (key.equals("disclaimer")) {
                parser.nextEvent();
                disclaimer = parser.getString();
              } else if(key.equals("license")) {
                parser.nextEvent();
                license = parser.getString();
              } else if(key.equals("timestamp")) {
                parser.nextEvent();
              } else if(key.equals("base")) {
                parser.nextEvent();
                baseCurrency = parser.getString();
              } else if(key.equals("rates")) {
                ev = parser.nextEvent();
                assert(ev == JSONParser.OBJECT_START);
                ev = parser.nextEvent();
                while (ev != JSONParser.OBJECT_END) {
                  String curr = parser.getString();
                  ev = parser.nextEvent();
                  Double rate = parser.getDouble();
                  rates.put(curr, rate);
                  ev = parser.nextEvent();
                }
              } else {
                log.warn("Unknown key {}", key);
              }
            } else {
              log.warn("Expected key, got {}", JSONParser.getEventString(ev));
            }
            break;

          case JSONParser.OBJECT_END:
          case JSONParser.OBJECT_START:
          case JSONParser.EOF:
            break;

          default:
            if (log.isInfoEnabled()) {
              log.info("Noggit UNKNOWN_EVENT_ID: {}", JSONParser.getEventString(ev));
            }
            break;
        }
      } while (ev != JSONParser.EOF);
    }

    public Map<String, Double> getRates() {
      return rates;
    }

    public String getDisclaimer() {
      return disclaimer;
    }

    public String getBaseCurrency() {
      return baseCurrency;
    }

    public String getLicense() {
      return license;
    }
  }
}
