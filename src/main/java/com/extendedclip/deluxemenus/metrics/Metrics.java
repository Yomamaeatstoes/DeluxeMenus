//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.extendedclip.deluxemenus.metrics;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.HttpsURLConnection;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class Metrics {
  private final Plugin plugin;
  private final MetricsBase metricsBase;

  public Metrics(JavaPlugin plugin, int serviceId) {
    this.plugin = plugin;
    File bStatsFolder = new File(plugin.getDataFolder().getParentFile(), "bStats");
    File configFile = new File(bStatsFolder, "config.yml");
    YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
    if (!config.isSet("serverUuid")) {
      config.addDefault("enabled", true);
      config.addDefault("serverUuid", UUID.randomUUID().toString());
      config.addDefault("logFailedRequests", false);
      config.addDefault("logSentData", false);
      config.addDefault("logResponseStatusText", false);
      config.options().header("bStats (https://bStats.org) collects some basic information for plugin authors, like how\nmany people use their plugin and their total player count. It's recommended to keep bStats\nenabled, but if you're not comfortable with this, you can turn this setting off. There is no\nperformance penalty associated with having metrics enabled, and data sent to bStats is fully\nanonymous.").copyDefaults(true);

      try {
        config.save(configFile);
      } catch (IOException var11) {
      }
    }

    boolean enabled = config.getBoolean("enabled", true);
    String serverUUID = config.getString("serverUuid");
    boolean logErrors = config.getBoolean("logFailedRequests", false);
    boolean logSentData = config.getBoolean("logSentData", false);
    boolean logResponseStatusText = config.getBoolean("logResponseStatusText", false);
    Consumer var10007 = this::appendPlatformData;
    Consumer var10008 = this::appendServiceData;
    Consumer var10009 = (submitDataTask) -> {
      Bukkit.getScheduler().runTask(plugin, submitDataTask);
    };
    Objects.requireNonNull(plugin);
    this.metricsBase = new MetricsBase("bukkit", serverUUID, serviceId, enabled, var10007, var10008, var10009, plugin::isEnabled, (message, error) -> {
      this.plugin.getLogger().log(Level.WARNING, message, error);
    }, (message) -> {
      this.plugin.getLogger().log(Level.INFO, message);
    }, logErrors, logSentData, logResponseStatusText);
  }

  public void addCustomChart(CustomChart chart) {
    this.metricsBase.addCustomChart(chart);
  }

  private void appendPlatformData(JsonObjectBuilder builder) {
    builder.appendField("playerAmount", this.getPlayerAmount());
    builder.appendField("onlineMode", Bukkit.getOnlineMode() ? 1 : 0);
    builder.appendField("bukkitVersion", Bukkit.getVersion());
    builder.appendField("bukkitName", Bukkit.getName());
    builder.appendField("javaVersion", System.getProperty("java.version"));
    builder.appendField("osName", System.getProperty("os.name"));
    builder.appendField("osArch", System.getProperty("os.arch"));
    builder.appendField("osVersion", System.getProperty("os.version"));
    builder.appendField("coreCount", Runtime.getRuntime().availableProcessors());
  }

  private void appendServiceData(JsonObjectBuilder builder) {
    builder.appendField("pluginVersion", this.plugin.getDescription().getVersion());
  }

  private int getPlayerAmount() {
    try {
      Method onlinePlayersMethod = Class.forName("org.bukkit.Server").getMethod("getOnlinePlayers");
      return onlinePlayersMethod.getReturnType().equals(Collection.class) ? ((Collection)onlinePlayersMethod.invoke(Bukkit.getServer())).size() : ((Player[])onlinePlayersMethod.invoke(Bukkit.getServer())).length;
    } catch (Exception var2) {
      return Bukkit.getOnlinePlayers().size();
    }
  }

  public static class MetricsBase {
    public static final String METRICS_VERSION = "2.2.1";
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, (task) -> {
      return new Thread(task, "bStats-Metrics");
    });
    private static final String REPORT_URL = "https://bStats.org/api/v2/data/%s";
    private final String platform;
    private final String serverUuid;
    private final int serviceId;
    private final Consumer<JsonObjectBuilder> appendPlatformDataConsumer;
    private final Consumer<JsonObjectBuilder> appendServiceDataConsumer;
    private final Consumer<Runnable> submitTaskConsumer;
    private final Supplier<Boolean> checkServiceEnabledSupplier;
    private final BiConsumer<String, Throwable> errorLogger;
    private final Consumer<String> infoLogger;
    private final boolean logErrors;
    private final boolean logSentData;
    private final boolean logResponseStatusText;
    private final Set<CustomChart> customCharts = new HashSet();
    private final boolean enabled;

    public MetricsBase(String platform, String serverUuid, int serviceId, boolean enabled, Consumer<JsonObjectBuilder> appendPlatformDataConsumer, Consumer<JsonObjectBuilder> appendServiceDataConsumer, Consumer<Runnable> submitTaskConsumer, Supplier<Boolean> checkServiceEnabledSupplier, BiConsumer<String, Throwable> errorLogger, Consumer<String> infoLogger, boolean logErrors, boolean logSentData, boolean logResponseStatusText) {
      this.platform = platform;
      this.serverUuid = serverUuid;
      this.serviceId = serviceId;
      this.enabled = enabled;
      this.appendPlatformDataConsumer = appendPlatformDataConsumer;
      this.appendServiceDataConsumer = appendServiceDataConsumer;
      this.submitTaskConsumer = submitTaskConsumer;
      this.checkServiceEnabledSupplier = checkServiceEnabledSupplier;
      this.errorLogger = errorLogger;
      this.infoLogger = infoLogger;
      this.logErrors = logErrors;
      this.logSentData = logSentData;
      this.logResponseStatusText = logResponseStatusText;
      this.checkRelocation();
      if (enabled) {
        this.startSubmitting();
      }

    }

    private static byte[] compress(String str) throws IOException {
      if (str == null) {
        return null;
      } else {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(outputStream);

        try {
          gzip.write(str.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable var6) {
          try {
            gzip.close();
          } catch (Throwable var5) {
            var6.addSuppressed(var5);
          }

          throw var6;
        }

        gzip.close();
        return outputStream.toByteArray();
      }
    }

    public void addCustomChart(CustomChart chart) {
      this.customCharts.add(chart);
    }

    private void startSubmitting() {
      Runnable submitTask = () -> {
        if (this.enabled && (Boolean)this.checkServiceEnabledSupplier.get()) {
          if (this.submitTaskConsumer != null) {
            this.submitTaskConsumer.accept(this::submitData);
          } else {
            this.submitData();
          }

        } else {
          scheduler.shutdown();
        }
      };
      long initialDelay = (long)(60000.0 * (3.0 + Math.random() * 3.0));
      long secondDelay = (long)(60000.0 * Math.random() * 30.0);
      scheduler.schedule(submitTask, initialDelay, TimeUnit.MILLISECONDS);
      scheduler.scheduleAtFixedRate(submitTask, initialDelay + secondDelay, 1800000L, TimeUnit.MILLISECONDS);
    }

    private void submitData() {
      JsonObjectBuilder baseJsonBuilder = new JsonObjectBuilder();
      this.appendPlatformDataConsumer.accept(baseJsonBuilder);
      JsonObjectBuilder serviceJsonBuilder = new JsonObjectBuilder();
      this.appendServiceDataConsumer.accept(serviceJsonBuilder);
      JsonObjectBuilder.JsonObject[] chartData = (JsonObjectBuilder.JsonObject[])this.customCharts.stream().map((customChart) -> {
        return customChart.getRequestJsonObject(this.errorLogger, this.logErrors);
      }).filter(Objects::nonNull).toArray((x$0) -> {
        return new JsonObjectBuilder.JsonObject[x$0];
      });
      serviceJsonBuilder.appendField("id", this.serviceId);
      serviceJsonBuilder.appendField("customCharts", chartData);
      baseJsonBuilder.appendField("service", serviceJsonBuilder.build());
      baseJsonBuilder.appendField("serverUUID", this.serverUuid);
      baseJsonBuilder.appendField("metricsVersion", "2.2.1");
      JsonObjectBuilder.JsonObject data = baseJsonBuilder.build();
      scheduler.execute(() -> {
        try {
          this.sendData(data);
        } catch (Exception var3) {
          if (this.logErrors) {
            this.errorLogger.accept("Could not submit bStats metrics data", var3);
          }
        }

      });
    }

    private void sendData(JsonObjectBuilder.JsonObject data) throws Exception {
      if (this.logSentData) {
        this.infoLogger.accept("Sent bStats metrics data: " + data.toString());
      }

      String url = String.format("https://bStats.org/api/v2/data/%s", this.platform);
      HttpsURLConnection connection = (HttpsURLConnection)(new URL(url)).openConnection();
      byte[] compressedData = compress(data.toString());
      connection.setRequestMethod("POST");
      connection.addRequestProperty("Accept", "application/json");
      connection.addRequestProperty("Connection", "close");
      connection.addRequestProperty("Content-Encoding", "gzip");
      connection.addRequestProperty("Content-Length", String.valueOf(compressedData.length));
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("User-Agent", "Metrics-Service/1");
      connection.setDoOutput(true);
      DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());

      try {
        outputStream.write(compressedData);
      } catch (Throwable var11) {
        try {
          outputStream.close();
        } catch (Throwable var10) {
          var11.addSuppressed(var10);
        }

        throw var11;
      }

      outputStream.close();
      StringBuilder builder = new StringBuilder();
      BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

      String line;
      try {
        while((line = bufferedReader.readLine()) != null) {
          builder.append(line);
        }
      } catch (Throwable var12) {
        try {
          bufferedReader.close();
        } catch (Throwable var9) {
          var12.addSuppressed(var9);
        }

        throw var12;
      }

      bufferedReader.close();
      if (this.logResponseStatusText) {
        this.infoLogger.accept("Sent data to bStats and received response: " + builder);
      }

    }

    private void checkRelocation() {
      if (System.getProperty("bstats.relocatecheck") == null || !System.getProperty("bstats.relocatecheck").equals("false")) {
        String defaultPackage = new String(new byte[]{111, 114, 103, 46, 98, 115, 116, 97, 116, 115});
        String examplePackage = new String(new byte[]{121, 111, 117, 114, 46, 112, 97, 99, 107, 97, 103, 101});
        if (MetricsBase.class.getPackage().getName().startsWith(defaultPackage) || MetricsBase.class.getPackage().getName().startsWith(examplePackage)) {
          throw new IllegalStateException("bStats Metrics class has not been relocated correctly!");
        }
      }

    }
  }

  public abstract static class CustomChart {
    private final String chartId;

    protected CustomChart(String chartId) {
      if (chartId == null) {
        throw new IllegalArgumentException("chartId must not be null");
      } else {
        this.chartId = chartId;
      }
    }

    public JsonObjectBuilder.JsonObject getRequestJsonObject(BiConsumer<String, Throwable> errorLogger, boolean logErrors) {
      JsonObjectBuilder builder = new JsonObjectBuilder();
      builder.appendField("chartId", this.chartId);

      try {
        JsonObjectBuilder.JsonObject data = this.getChartData();
        if (data == null) {
          return null;
        }

        builder.appendField("data", data);
      } catch (Throwable var5) {
        if (logErrors) {
          errorLogger.accept("Failed to get data for custom chart with id " + this.chartId, var5);
        }

        return null;
      }

      return builder.build();
    }

    protected abstract JsonObjectBuilder.JsonObject getChartData() throws Exception;
  }

  public static class JsonObjectBuilder {
    private StringBuilder builder = new StringBuilder();
    private boolean hasAtLeastOneField = false;

    public JsonObjectBuilder() {
      this.builder.append("{");
    }

    private static String escape(String value) {
      StringBuilder builder = new StringBuilder();

      for(int i = 0; i < value.length(); ++i) {
        char c = value.charAt(i);
        if (c == '"') {
          builder.append("\\\"");
        } else if (c == '\\') {
          builder.append("\\\\");
        } else if (c <= 15) {
          builder.append("\\u000").append(Integer.toHexString(c));
        } else if (c <= 31) {
          builder.append("\\u00").append(Integer.toHexString(c));
        } else {
          builder.append(c);
        }
      }

      return builder.toString();
    }

    public JsonObjectBuilder appendNull(String key) {
      this.appendFieldUnescaped(key, "null");
      return this;
    }

    public JsonObjectBuilder appendField(String key, String value) {
      if (value == null) {
        throw new IllegalArgumentException("JSON value must not be null");
      } else {
        this.appendFieldUnescaped(key, "\"" + escape(value) + "\"");
        return this;
      }
    }

    public JsonObjectBuilder appendField(String key, int value) {
      this.appendFieldUnescaped(key, String.valueOf(value));
      return this;
    }

    public JsonObjectBuilder appendField(String key, JsonObject object) {
      if (object == null) {
        throw new IllegalArgumentException("JSON object must not be null");
      } else {
        this.appendFieldUnescaped(key, object.toString());
        return this;
      }
    }

    public JsonObjectBuilder appendField(String key, String[] values) {
      if (values == null) {
        throw new IllegalArgumentException("JSON values must not be null");
      } else {
        String escapedValues = (String)Arrays.stream(values).map((value) -> {
          return "\"" + escape(value) + "\"";
        }).collect(Collectors.joining(","));
        this.appendFieldUnescaped(key, "[" + escapedValues + "]");
        return this;
      }
    }

    public JsonObjectBuilder appendField(String key, int[] values) {
      if (values == null) {
        throw new IllegalArgumentException("JSON values must not be null");
      } else {
        String escapedValues = (String)Arrays.stream(values).mapToObj(String::valueOf).collect(Collectors.joining(","));
        this.appendFieldUnescaped(key, "[" + escapedValues + "]");
        return this;
      }
    }

    public JsonObjectBuilder appendField(String key, JsonObject[] values) {
      if (values == null) {
        throw new IllegalArgumentException("JSON values must not be null");
      } else {
        String escapedValues = (String)Arrays.stream(values).map(JsonObject::toString).collect(Collectors.joining(","));
        this.appendFieldUnescaped(key, "[" + escapedValues + "]");
        return this;
      }
    }

    private void appendFieldUnescaped(String key, String escapedValue) {
      if (this.builder == null) {
        throw new IllegalStateException("JSON has already been built");
      } else if (key == null) {
        throw new IllegalArgumentException("JSON key must not be null");
      } else {
        if (this.hasAtLeastOneField) {
          this.builder.append(",");
        }

        this.builder.append("\"").append(escape(key)).append("\":").append(escapedValue);
        this.hasAtLeastOneField = true;
      }
    }

    public JsonObject build() {
      if (this.builder == null) {
        throw new IllegalStateException("JSON has already been built");
      } else {
        JsonObject object = new JsonObject(this.builder.append("}").toString());
        this.builder = null;
        return object;
      }
    }

    public static class JsonObject {
      private final String value;

      private JsonObject(String value) {
        this.value = value;
      }

      public String toString() {
        return this.value;
      }
    }
  }

  public static class DrilldownPie extends CustomChart {
    private final Callable<Map<String, Map<String, Integer>>> callable;

    public DrilldownPie(String chartId, Callable<Map<String, Map<String, Integer>>> callable) {
      super(chartId);
      this.callable = callable;
    }

    public JsonObjectBuilder.JsonObject getChartData() throws Exception {
      JsonObjectBuilder valuesBuilder = new JsonObjectBuilder();
      Map<String, Map<String, Integer>> map = (Map)this.callable.call();
      if (map != null && !map.isEmpty()) {
        boolean reallyAllSkipped = true;
        Iterator var4 = map.entrySet().iterator();

        while(var4.hasNext()) {
          Map.Entry<String, Map<String, Integer>> entryValues = (Map.Entry)var4.next();
          JsonObjectBuilder valueBuilder = new JsonObjectBuilder();
          boolean allSkipped = true;

          for(Iterator var8 = ((Map)map.get(entryValues.getKey())).entrySet().iterator(); var8.hasNext(); allSkipped = false) {
            Map.Entry<String, Integer> valueEntry = (Map.Entry)var8.next();
            valueBuilder.appendField((String)valueEntry.getKey(), (Integer)valueEntry.getValue());
          }

          if (!allSkipped) {
            reallyAllSkipped = false;
            valuesBuilder.appendField((String)entryValues.getKey(), valueBuilder.build());
          }
        }

        if (reallyAllSkipped) {
          return null;
        } else {
          return (new JsonObjectBuilder()).appendField("values", valuesBuilder.build()).build();
        }
      } else {
        return null;
      }
    }
  }

  public static class SimplePie extends CustomChart {
    private final Callable<String> callable;

    public SimplePie(String chartId, Callable<String> callable) {
      super(chartId);
      this.callable = callable;
    }

    protected JsonObjectBuilder.JsonObject getChartData() throws Exception {
      String value = (String)this.callable.call();
      return value != null && !value.isEmpty() ? (new JsonObjectBuilder()).appendField("value", value).build() : null;
    }
  }

  public static class SingleLineChart extends CustomChart {
    private final Callable<Integer> callable;

    public SingleLineChart(String chartId, Callable<Integer> callable) {
      super(chartId);
      this.callable = callable;
    }

    protected JsonObjectBuilder.JsonObject getChartData() throws Exception {
      int value = (Integer)this.callable.call();
      return value == 0 ? null : (new JsonObjectBuilder()).appendField("value", value).build();
    }
  }

  public static class AdvancedPie extends CustomChart {
    private final Callable<Map<String, Integer>> callable;

    public AdvancedPie(String chartId, Callable<Map<String, Integer>> callable) {
      super(chartId);
      this.callable = callable;
    }

    protected JsonObjectBuilder.JsonObject getChartData() throws Exception {
      JsonObjectBuilder valuesBuilder = new JsonObjectBuilder();
      Map<String, Integer> map = (Map)this.callable.call();
      if (map != null && !map.isEmpty()) {
        boolean allSkipped = true;
        Iterator var4 = map.entrySet().iterator();

        while(var4.hasNext()) {
          Map.Entry<String, Integer> entry = (Map.Entry)var4.next();
          if ((Integer)entry.getValue() != 0) {
            allSkipped = false;
            valuesBuilder.appendField((String)entry.getKey(), (Integer)entry.getValue());
          }
        }

        if (allSkipped) {
          return null;
        } else {
          return (new JsonObjectBuilder()).appendField("values", valuesBuilder.build()).build();
        }
      } else {
        return null;
      }
    }
  }

  public static class MultiLineChart extends CustomChart {
    private final Callable<Map<String, Integer>> callable;

    public MultiLineChart(String chartId, Callable<Map<String, Integer>> callable) {
      super(chartId);
      this.callable = callable;
    }

    protected JsonObjectBuilder.JsonObject getChartData() throws Exception {
      JsonObjectBuilder valuesBuilder = new JsonObjectBuilder();
      Map<String, Integer> map = (Map)this.callable.call();
      if (map != null && !map.isEmpty()) {
        boolean allSkipped = true;
        Iterator var4 = map.entrySet().iterator();

        while(var4.hasNext()) {
          Map.Entry<String, Integer> entry = (Map.Entry)var4.next();
          if ((Integer)entry.getValue() != 0) {
            allSkipped = false;
            valuesBuilder.appendField((String)entry.getKey(), (Integer)entry.getValue());
          }
        }

        if (allSkipped) {
          return null;
        } else {
          return (new JsonObjectBuilder()).appendField("values", valuesBuilder.build()).build();
        }
      } else {
        return null;
      }
    }
  }

  public static class SimpleBarChart extends CustomChart {
    private final Callable<Map<String, Integer>> callable;

    public SimpleBarChart(String chartId, Callable<Map<String, Integer>> callable) {
      super(chartId);
      this.callable = callable;
    }

    protected JsonObjectBuilder.JsonObject getChartData() throws Exception {
      JsonObjectBuilder valuesBuilder = new JsonObjectBuilder();
      Map<String, Integer> map = (Map)this.callable.call();
      if (map != null && !map.isEmpty()) {
        Iterator var3 = map.entrySet().iterator();

        while(var3.hasNext()) {
          Map.Entry<String, Integer> entry = (Map.Entry)var3.next();
          valuesBuilder.appendField((String)entry.getKey(), new int[]{(Integer)entry.getValue()});
        }

        return (new JsonObjectBuilder()).appendField("values", valuesBuilder.build()).build();
      } else {
        return null;
      }
    }
  }

  public static class AdvancedBarChart extends CustomChart {
    private final Callable<Map<String, int[]>> callable;

    public AdvancedBarChart(String chartId, Callable<Map<String, int[]>> callable) {
      super(chartId);
      this.callable = callable;
    }

    protected JsonObjectBuilder.JsonObject getChartData() throws Exception {
      JsonObjectBuilder valuesBuilder = new JsonObjectBuilder();
      Map<String, int[]> map = (Map)this.callable.call();
      if (map != null && !map.isEmpty()) {
        boolean allSkipped = true;
        Iterator var4 = map.entrySet().iterator();

        while(var4.hasNext()) {
          Map.Entry<String, int[]> entry = (Map.Entry)var4.next();
          if (((int[])entry.getValue()).length != 0) {
            allSkipped = false;
            valuesBuilder.appendField((String)entry.getKey(), (int[])entry.getValue());
          }
        }

        if (allSkipped) {
          return null;
        } else {
          return (new JsonObjectBuilder()).appendField("values", valuesBuilder.build()).build();
        }
      } else {
        return null;
      }
    }
  }
}
