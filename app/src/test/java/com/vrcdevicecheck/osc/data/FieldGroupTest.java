package com.vrcdevicecheck.osc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;

public class FieldGroupTest {

    @Test
    public void everyFieldBelongsToExactlyOneGroup() {
        for (ReportField field : ReportField.values()) {
            int matches = 0;
            for (FieldGroup group : FieldGroup.values()) {
                if (group.contains(field)) matches++;
            }
            assertEquals(field.name() + " 必须且只能属于一个分组", 1, matches);
        }
    }

    @Test
    public void groupsAreNotEmpty() {
        for (FieldGroup group : FieldGroup.values()) {
            assertTrue(group.key, group.fieldCount() > 0);
        }
    }

    @Test
    public void selectedCountOnlyCountsItsOwnFields() {
        EnumSet<ReportField> enabled = EnumSet.of(
                ReportField.MUSIC_TITLE, ReportField.MUSIC_LYRIC,
                ReportField.BATTERY, ReportField.CPU_NAME);
        assertEquals(2, FieldGroup.MUSIC.selectedCount(enabled));
        assertEquals(2, FieldGroup.HARDWARE.selectedCount(enabled));
        assertEquals(0, FieldGroup.WEATHER.selectedCount(enabled));
    }
}