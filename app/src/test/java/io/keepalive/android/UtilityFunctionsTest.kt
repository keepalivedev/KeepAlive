package io.keepalive.android

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import android.content.SharedPreferences
import com.google.gson.Gson
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner


// lets try the easy stuff first...
@RunWith(MockitoJUnitRunner::class)
class UtilityFunctionsTest {

    @Mock
    private lateinit var mockSharedPrefs: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    @Mock
    private lateinit var gson: Gson

    @Test
    fun loadSMSEmergencyContactSettings_correctlyLoadsSettings() {
        // test data
        val testJson = "[{\"phoneNumber\":\"1234567890\",\"alertMessage\":\"Help!\",\"isEnabled\":true,\"includeLocation\":true}]"
        `when`(mockSharedPrefs.getString("PHONE_NUMBER_SETTINGS", null)).thenReturn(testJson)

        // Act
        val result = loadSMSEmergencyContactSettings(mockSharedPrefs)

        // Assert
        assertNotNull(result)
        assertEquals(1, result.size)
        assertEquals("1234567890", result[0].phoneNumber)
        assertEquals("Help!", result[0].alertMessage)
        assertTrue(result[0].isEnabled)
        assertTrue(result[0].includeLocation)
    }

    @Test
    fun saveSMSEmergencyContactSettings_savesCorrectJsonString() {

        // the settings to save
        val phoneNumberList = mutableListOf(
            SMSEmergencyContactSetting(
                phoneNumber = "123-456-7890",
                alertMessage = "Help!",
                isEnabled = true,
                includeLocation = true
            ),
            SMSEmergencyContactSetting(
                phoneNumber = "098-765-4321",
                alertMessage = "Hello!",
                isEnabled = true,
                includeLocation = false
            )
        )

        `when`(mockSharedPrefs.edit()).thenReturn(mockEditor)
        //`when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)

        val expectedJson = gson.toJson(phoneNumberList)

        // Execute
        saveSMSEmergencyContactSettings(mockSharedPrefs, phoneNumberList, gson)

        // this seems to verify the call but doesn't actually save it to mockSharedPrefs
        verify(mockEditor).putString("PHONE_NUMBER_SETTINGS", expectedJson)
        verify(mockEditor).putBoolean("location_enabled", true)
        verify(mockEditor).apply()
    }
}