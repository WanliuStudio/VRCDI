package com.vrcdevicecheck.osc.osc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.vrcdevicecheck.osc.data.ReportField;
import com.vrcdevicecheck.osc.device.DeviceSnapshot;
import com.vrcdevicecheck.osc.device.TestSnapshots;
import com.vrcdevicecheck.osc.media.MusicInfo;
import com.vrcdevicecheck.osc.weather.WeatherInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

public class ReportBuilderTest {

    @Test
    public void rendersGroupsInSeparateLines() {
        DeviceSnapshot snapshot = TestSnapshots.full();
        Set<ReportField> fields = EnumSet.of(
                ReportField.BRAND, ReportField.MODEL,
                ReportField.CPU_NAME, ReportField.CPU_FREQ, ReportField.CPU_TEMP, ReportField.CPU_USAGE,
                ReportField.RAM, ReportField.BATTERY);
        String text = ReportBuilder.build(snapshot, fields, false).text;
        assertEquals("HUAWEI Pura 80\n"
                + "CPU: Kirin 9010S 1.80GHz 42.3℃ 23%\n"
                + "RAM: 6.1/11.7GB 52%\n"
                + "电量: 78% 充电中", text);
    }

    @Test
    public void chargingLabelOnlyAppearsWhenActuallyCharging() {
        DeviceSnapshot charging = TestSnapshots.full();
        assertTrue(ReportBuilder.build(charging, EnumSet.of(ReportField.BATTERY), false).text.endsWith("充电中"));

        DeviceSnapshot notCharging = TestSnapshots.notCharging(66);
        String text = ReportBuilder.build(notCharging, EnumSet.of(ReportField.BATTERY), false).text;
        assertEquals("电量: 66%", text);
        assertFalse(text.contains("充电中"));
    }

    @Test
    public void asciiOnlyReplacesNonAsciiLabels() {
        DeviceSnapshot snapshot = TestSnapshots.full();
        String text = ReportBuilder.build(snapshot, EnumSet.of(ReportField.CPU_TEMP, ReportField.BATTERY), true).text;
        assertTrue(text.contains("42.3C"));
        assertTrue(text.contains("Charging"));
        assertFalse(text.contains("℃"));
        assertFalse(text.contains("充电中"));
    }

    @Test
    public void unavailableValuesRenderPlaceholderNeverFakeNumbers() {
        String text = ReportBuilder.build(TestSnapshots.empty(), EnumSet.allOf(ReportField.class), false).text;
        assertTrue(text.contains("不可用"));
        assertFalse(text.contains("0%"));
    }

    @Test
    public void noFieldsSelectedProducesEmptyText() {
        ReportBuilder.Report report =
                ReportBuilder.build(TestSnapshots.full(), EnumSet.noneOf(ReportField.class), false);
        assertEquals("", report.text);
    }

    @Test
    public void longTextDropsWholeLinesFromTheEnd() {
        DeviceSnapshot snapshot = new DeviceSnapshot(
                "HUAWEI", "HED-AL00", "Pura 80",
                "Kirin 9010S 超高性能旗舰版移动处理器平台", 1800, 42.3f, 23f,
                "Maleoon 910 超高性能图形处理器单元", 750, 40.1f, 12f,
                6200L, 12000L,
                78, true,
                2000, new java.util.ArrayList<String>(), "Shizuku");

        ReportBuilder.Report report = ReportBuilder.build(snapshot, EnumSet.allOf(ReportField.class), false);

        assertTrue("长度必须不超过 144，实际 " + report.charCount(),
                report.charCount() <= ReportBuilder.CHATBOX_LIMIT);
        assertFalse(report.dropped.isEmpty());
        // 第一行永远保留
        assertTrue(report.included.contains(ReportField.BRAND));
        // 最后一段（VRChat 帧率）最先被整段丢掉
        assertTrue(report.dropped.contains(ReportField.VRCHAT_FPS));
        assertTrue(report.overLimit());
    }

    @Test
    public void shortTextReportsNothingDropped() {
        ReportBuilder.Report report = ReportBuilder.build(
                TestSnapshots.notCharging(50), EnumSet.of(ReportField.BATTERY), false);
        assertTrue(report.dropped.isEmpty());
        assertFalse(report.overLimit());
    }

    @Test
    public void gpuGroupGetsItsOwnLabel() {
        String text = ReportBuilder.render(TestSnapshots.full(),
                Arrays.asList(ReportField.GPU_NAME, ReportField.GPU_USAGE), false);
        assertEquals("GPU: Maleoon 910 12%", text);
    }

    @Test
    public void musicLineCombinesSelectedPartsAndLyric() {
        MusicInfo music = new MusicInfo("夜曲", "周杰伦", "十一月的萧邦", "一群嗜血的蚂蚁");
        ReportBuilder.Report report = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.MUSIC_TITLE, ReportField.MUSIC_ARTIST,
                        ReportField.MUSIC_ALBUM, ReportField.MUSIC_LYRIC),
                false, true, null, false, "zh", music);

        assertEquals("♪ 夜曲 - 周杰伦 (十一月的萧邦)\n♪ 一群嗜血的蚂蚁", report.text);
        assertTrue(report.included.contains(ReportField.MUSIC_LYRIC));
        assertTrue(report.dropped.isEmpty());
    }

    @Test
    public void heartRateLineRendersBpm() {
        String text = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.HEART_RATE),
                false, true, null, false, "en", null, 78).text;
        assertEquals("Heart rate: 78bpm", text);
    }

    @Test
    public void heartRateWithoutDataIsHidden() {
        ReportBuilder.Report report = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.HEART_RATE),
                false, true, null, true, "zh", null, null);
        assertEquals("", report.text);
        assertTrue(report.hidden.contains(ReportField.HEART_RATE));
    }

    @Test
    public void musicLineShowsPlaybackProgress() {
        MusicInfo music = new MusicInfo("夜曲", "周杰伦", "十一月的萧邦", "一群嗜血的蚂蚁",
                80000L, 260000L);
        String text = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.MUSIC_TITLE, ReportField.MUSIC_PROGRESS),
                false, true, null, false, "zh", music).text;

        assertEquals("♪ 夜曲 1:20 / 4:20", text);
    }

    @Test
    public void musicLineOnlyUsesSelectedParts() {
        MusicInfo music = new MusicInfo("夜曲", "周杰伦", "十一月的萧邦", "一群嗜血的蚂蚁");
        String text = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.MUSIC_ARTIST, ReportField.MUSIC_LYRIC),
                false, true, null, false, "zh", music).text;

        assertEquals("♪ 周杰伦\n♪ 一群嗜血的蚂蚁", text);
    }

    @Test
    public void musicInAsciiModeUsesAsciiPrefix() {
        MusicInfo music = new MusicInfo("Nocturne", "Jay Chou", "November", "Ants");
        String text = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.MUSIC_TITLE, ReportField.MUSIC_ARTIST,
                        ReportField.MUSIC_ALBUM, ReportField.MUSIC_LYRIC),
                true, true, null, false, "zh", music).text;

        assertEquals("Music: Nocturne - Jay Chou (November)\nLyric: Ants", text);
    }

    @Test
    public void musicFieldsHiddenWhenNothingPlaying() {
        ReportBuilder.Report report = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.MUSIC_TITLE, ReportField.MUSIC_LYRIC),
                false, true, null, true, "zh", null);

        assertEquals("", report.text);
        assertTrue(report.hidden.contains(ReportField.MUSIC_TITLE));
        assertTrue(report.hidden.contains(ReportField.MUSIC_LYRIC));
        assertTrue(report.dropped.isEmpty());
    }

    private WeatherInfo weather() {
        return new WeatherInfo("江西省 上饶市", 17.3f, 86,
                "多云", "Partly cloudy", "晴れ時々曇り", "Переменная облачность", "구름 조금",
                0L, "Open-Meteo", null, null, null, null, null);
    }

    @Test
    public void weatherLineCombinesConditionTemperatureAndHumidity() {
        String text = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.WEATHER, ReportField.TEMPERATURE, ReportField.HUMIDITY),
                false, true, weather()).text;
        assertEquals("天气: 多云 17.3℃ 湿度 86%", text);
    }

    @Test
    public void weatherLineInAsciiMode() {
        String text = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.WEATHER, ReportField.TEMPERATURE, ReportField.HUMIDITY),
                true, true, weather()).text;
        assertEquals("Weather: Partly cloudy 17.3C Humidity 86%", text);
    }

    @Test
    public void weatherLineOnlyIncludesSelectedParts() {
        String text = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.TEMPERATURE), false, true, weather()).text;
        assertEquals("天气: 17.3℃", text);
    }

    @Test
    public void weatherWithoutDataShowsPlaceholder() {
        String text = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.WEATHER, ReportField.TEMPERATURE),
                false, true, null).text;
        assertEquals("天气: 不可用", text);
    }

    private WeatherInfo weatherWithDetails() {
        return new WeatherInfo("江西省 上饶市", 17.3f, 86,
                "多云", "Partly cloudy", "晴れ時々曇り", "Переменная облачность", "구름 조금",
                0L, "Open-Meteo",
                1018f, 225, 3.2f, 5, "17:14");
    }

    @Test
    public void weatherSecondLineIncludesPressureWindUvSunset() {
        String text = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.PRESSURE, ReportField.WIND,
                        ReportField.UV, ReportField.SUNSET),
                false, true, weatherWithDetails(), false).text;
        assertEquals("气压 1018hPa 西南风 3.2m/s 紫外线 5 日落 17:14", text);
    }

    @Test
    public void weatherSecondLineInAsciiMode() {
        String text = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.PRESSURE, ReportField.WIND,
                        ReportField.UV, ReportField.SUNSET),
                true, true, weatherWithDetails(), false).text;
        assertEquals("Pressure 1018hPa SW wind 3.2m/s UV 5 Sunset 17:14", text);
    }

    @Test
    public void windWithoutDirectionStillShowsSpeed() {
        WeatherInfo weather = new WeatherInfo("上饶市", 17.3f, 86,
                "多云", "Partly cloudy", "晴れ時々曇り", "Переменная облачность", "구름 조금",
                0L, "Open-Meteo",
                null, null, 3.2f, null, null);
        String text = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.WIND), false, true, weather, false).text;
        assertEquals("风速 3.2m/s", text);
    }

    @Test
    public void hideUnavailableDropsWholeEmptyReport() {
        ReportBuilder.Report report = ReportBuilder.build(TestSnapshots.empty(),
                EnumSet.of(ReportField.BRAND, ReportField.MODEL, ReportField.CPU_NAME),
                false, true, null, true);

        assertEquals("", report.text);
        assertTrue(report.hidden.contains(ReportField.BRAND));
        assertTrue(report.hidden.contains(ReportField.MODEL));
        assertTrue(report.hidden.contains(ReportField.CPU_NAME));
        assertTrue(report.dropped.isEmpty());
    }

    @Test
    public void hideUnavailableKeepsAvailablePartsAndHidesOnlyNa() {
        ReportBuilder.Report report = ReportBuilder.build(TestSnapshots.notCharging(66),
                EnumSet.of(ReportField.BRAND, ReportField.CPU_NAME, ReportField.CPU_TEMP),
                false, true, null, true);

        assertEquals("zte", report.text);
        assertTrue(report.included.contains(ReportField.BRAND));
        assertTrue(report.hidden.contains(ReportField.CPU_NAME));
        assertTrue(report.hidden.contains(ReportField.CPU_TEMP));
        assertTrue(report.dropped.isEmpty());
    }

    @Test
    public void hidingCanBeDisabled() {
        ReportBuilder.Report report = ReportBuilder.build(TestSnapshots.empty(),
                EnumSet.of(ReportField.BRAND), false, true, null, false);

        assertEquals("不可用", report.text);
        assertTrue(report.hidden.isEmpty());
        assertTrue(report.dropped.isEmpty());
    }

    @Test
    public void reportFollowsSelectedLanguage() {
        assertEquals("Battery: 78% Charging", ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.BATTERY), false, true, null, false, "en").text);
        assertEquals("バッテリー: 78% 充電中", ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.BATTERY), false, true, null, false, "ja").text);
        assertEquals("Батарея: 78% Заряжается", ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.BATTERY), false, true, null, false, "ru").text);
        assertEquals("배터리: 78% 충전 중", ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.BATTERY), false, true, null, false, "ko").text);
        assertEquals("电量: 78% 充电中", ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.BATTERY), false, true, null, false, "zh").text);
    }

    @Test
    public void asciiOnlyOverridesLanguage() {
        String text = ReportBuilder.build(TestSnapshots.full(), EnumSet.of(ReportField.BATTERY),
                true, true, null, false, "ja").text;
        assertEquals("Battery: 78% Charging", text);
    }

    @Test
    public void weatherLineUsesSelectedLanguage() {
        assertEquals("天気: 晴れ時々曇り 17.3℃ 湿度 86%", ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.WEATHER, ReportField.TEMPERATURE, ReportField.HUMIDITY),
                false, true, weather(), false, "ja").text);
        assertEquals("Погода: Переменная облачность 17.3°C Влажность 86%",
                ReportBuilder.build(TestSnapshots.full(),
                        EnumSet.of(ReportField.WEATHER, ReportField.TEMPERATURE, ReportField.HUMIDITY),
                        false, true, weather(), false, "ru").text);
        assertEquals("날씨: 구름 조금 17.3℃ 습도 86%",
                ReportBuilder.build(TestSnapshots.full(),
                        EnumSet.of(ReportField.WEATHER, ReportField.TEMPERATURE, ReportField.HUMIDITY),
                        false, true, weather(), false, "ko").text);
    }

    @Test
    public void weatherFieldsAreHiddenWhenNoWeatherData() {
        ReportBuilder.Report report = ReportBuilder.build(TestSnapshots.full(),
                EnumSet.of(ReportField.WEATHER, ReportField.PRESSURE),
                false, true, null, true);

        assertEquals("", report.text);
        assertTrue(report.hidden.contains(ReportField.WEATHER));
        assertTrue(report.hidden.contains(ReportField.PRESSURE));
        assertTrue(report.dropped.isEmpty());
    }
}
