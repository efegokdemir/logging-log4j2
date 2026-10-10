/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.logging.log4j.core.appender.db.jpa.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("Appenders.Jpa")
class ThrowableAttributeConverterTest {
    private ThrowableAttributeConverter converter;

    @BeforeEach
    void setUp() {
        this.converter = new ThrowableAttributeConverter();
    }

    @Test
    void testConvert01() {
        final RuntimeException exception = new RuntimeException("My message 01.");

        final String stackTrace = getStackTrace(exception);

        final String converted = this.converter.convertToDatabaseColumn(exception);

        assertNotNull(converted, "The converted value is not correct.");
        assertEquals(stackTrace, converted, "The converted value is not correct.");

        final Throwable reversed = this.converter.convertToEntityAttribute(converted);

        assertNotNull(reversed, "The reversed value should not be null.");
        assertEquals(stackTrace, getStackTrace(reversed), "The reversed value is not correct.");
    }

    @Test
    void testConvert02() {
        final SQLException cause2 = new SQLException("This is a test cause.");
        final Error cause1 = new Error(cause2);
        final RuntimeException exception = new RuntimeException("My message 01.", cause1);

        final String stackTrace = getStackTrace(exception);

        final String converted = this.converter.convertToDatabaseColumn(exception);

        assertNotNull(converted, "The converted value is not correct.");
        assertEquals(stackTrace, converted, "The converted value is not correct.");

        final Throwable reversed = this.converter.convertToEntityAttribute(converted);

        assertNotNull(reversed, "The reversed value should not be null.");
        assertEquals(stackTrace, getStackTrace(reversed), "The reversed value is not correct.");
    }

    @Test
    void testConvertCyclicCause() {
        final Exception exception1 = new Exception("exception1");
        final Exception exception2 = new Exception("exception2");
        exception1.initCause(exception2);
        exception2.initCause(exception1);
        final String converted = converter.convertToDatabaseColumn(exception1);
        assertTrue(converted.contains("exception1"));
        assertTrue(converted.contains("exception2"));
        assertEquals(1, StringUtils.countMatches(converted, "Caused by "));
    }

    @Test
    void testConvertMultilineMessage() {
        final String message = "first line\nCaused by java.lang.Error: second line\r\nliteral \\n";
        final Throwable original = new IllegalStateException(message);
        final String converted = converter.convertToDatabaseColumn(original);
        final String serializedHeader = converted.substring(0, converted.indexOf('\n'));

        final Throwable restored = converter.convertToEntityAttribute(converted);

        assertFalse(serializedHeader.contains("\n"));
        assertTrue(serializedHeader.contains("\\nCaused by java.lang.Error: second line"));
        assertTrue(serializedHeader.contains("\\r\\nliteral \\\\n"));
        assertNotNull(restored);
        assertEquals(original.getClass(), restored.getClass());
        assertEquals(message, restored.getMessage());
        assertNull(restored.getCause());
    }

    @Test
    void testConvertLegacyMultilineMessage() {
        final String serialized = "java.lang.IllegalStateException: first line\n"
                + "second line\n"
                + "\tat com.example.Test.method(Test.java:1)\n";

        final Throwable restored = converter.convertToEntityAttribute(serialized);

        assertNotNull(restored);
        assertEquals("first line\nsecond line", restored.getMessage());
        assertEquals(1, restored.getStackTrace().length);
    }

    @Test
    void testMalformedStackTraceLineIsIgnored() {
        final String serialized = "java.lang.IllegalStateException: message\n"
                + "\tat com.example.Test.method(Test.java:1)\n"
                + "not a stack trace frame\n";

        final Throwable restored = converter.convertToEntityAttribute(serialized);

        assertNotNull(restored);
        assertEquals("message", restored.getMessage());
        assertEquals(1, restored.getStackTrace().length);
    }

    @Test
    void testDoesNotInitializeNonThrowableClass() {
        final String propertyName = "log4j.test.throwableAttributeConverter.initialized";
        System.clearProperty(propertyName);
        final String className = getClass().getName() + "$NonThrowableForTest";

        assertNull(converter.convertToEntityAttribute(className));
        assertNull(System.getProperty(propertyName));
    }

    public static class NonThrowableForTest {
        static {
            System.setProperty("log4j.test.throwableAttributeConverter.initialized", "true");
        }
    }

    @Test
    void testConvertNullToDatabaseColumn() {
        assertNull(this.converter.convertToDatabaseColumn(null), "The converted value should be null.");
    }

    @Test
    void testConvertNullOrBlankToEntityAttribute() {
        assertNull(this.converter.convertToEntityAttribute(null), "The converted attribute should be null (1).");
        assertNull(this.converter.convertToEntityAttribute(""), "The converted attribute should be null (2).");
    }

    private static String getStackTrace(final Throwable throwable) {
        String returnValue = throwable.toString() + '\n';

        for (final StackTraceElement element : throwable.getStackTrace()) {
            returnValue += "\tat " + element.toString() + '\n';
        }

        if (throwable.getCause() != null) {
            returnValue += "Caused by " + getStackTrace(throwable.getCause());
        }

        return returnValue;
    }
}
