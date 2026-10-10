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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import org.apache.logging.log4j.util.LoaderUtil;
import org.apache.logging.log4j.util.Strings;

/**
 * A JPA 2.1 attribute converter for {@link Throwable}s in {@link org.apache.logging.log4j.core.LogEvent}s. This
 * converter is capable of converting both to and from {@link String}s.
 */
@Converter(autoApply = false)
public class ThrowableAttributeConverter implements AttributeConverter<Throwable, String> {
    private static final StackTraceElement[] EMPTY_STACK_TRACE_ELEMENT_ARRAY = new StackTraceElement[0];

    private static final int CAUSED_BY_STRING_LENGTH = 10;

    private static final String ESCAPED_MESSAGE_PREFIX = "\u001bLog4jEscapedMessageV1:";

    private static final Field THROWABLE_CAUSE;

    private static final Field THROWABLE_MESSAGE;

    static {
        try {
            THROWABLE_CAUSE = Throwable.class.getDeclaredField("cause");
            THROWABLE_CAUSE.setAccessible(true);
            THROWABLE_MESSAGE = Throwable.class.getDeclaredField("detailMessage");
            THROWABLE_MESSAGE.setAccessible(true);
        } catch (final NoSuchFieldException e) {
            throw new IllegalStateException("Something is wrong with java.lang.Throwable.", e);
        }
    }

    @Override
    public String convertToDatabaseColumn(final Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        final StringBuilder builder = new StringBuilder();
        this.convertThrowable(builder, throwable);
        return builder.toString();
    }

    private void convertThrowable(final StringBuilder builder, final Throwable throwable) {
        final Set<Throwable> visitedThrowables = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable currentThrowable = throwable; visitedThrowables.add(currentThrowable); ) {
            appendThrowableHeader(builder, currentThrowable);
            builder.append('\n');
            for (final StackTraceElement element : currentThrowable.getStackTrace()) {
                builder.append("\tat ").append(element).append('\n');
            }
            final Throwable cause = currentThrowable.getCause();
            if (cause == null || visitedThrowables.contains(cause)) {
                break;
            }
            builder.append("Caused by ");
            currentThrowable = cause;
        }
    }

    private static void appendThrowableHeader(final StringBuilder builder, final Throwable throwable) {
        final String throwableText = throwable.toString();
        final String messagePrefix = throwable.getClass().getName() + ": ";
        if (throwableText.startsWith(messagePrefix)) {
            final String message = throwableText.substring(messagePrefix.length());
            if (message.indexOf('\n') >= 0
                    || message.indexOf('\r') >= 0
                    || message.startsWith(ESCAPED_MESSAGE_PREFIX)) {
                builder.append(messagePrefix).append(ESCAPED_MESSAGE_PREFIX);
                appendEscapedMessage(builder, message);
            } else {
                builder.append(throwableText);
            }
        } else {
            builder.append(throwableText);
        }
    }

    private static void appendEscapedMessage(final StringBuilder builder, final String message) {
        for (int i = 0; i < message.length(); i++) {
            final char character = message.charAt(i);
            switch (character) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\u001b':
                    builder.append("\\e");
                    break;
                default:
                    builder.append(character);
            }
        }
    }

    private static String unescapeMessage(final String message) {
        final StringBuilder builder = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            final char character = message.charAt(i);
            if (character == '\\' && i + 1 < message.length()) {
                switch (message.charAt(++i)) {
                    case '\\':
                        builder.append('\\');
                        break;
                    case 'n':
                        builder.append('\n');
                        break;
                    case 'r':
                        builder.append('\r');
                        break;
                    case 'e':
                        builder.append('\u001b');
                        break;
                    default:
                        builder.append('\\').append(message.charAt(i));
                }
            } else {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    @Override
    public Throwable convertToEntityAttribute(final String s) {
        if (Strings.isEmpty(s)) {
            return null;
        }

        final List<String> lines = Arrays.asList(s.split("\\r\\n|\\r|\\n"));
        return this.convertString(lines.listIterator(), false);
    }

    private Throwable convertString(final ListIterator<String> lines, final boolean removeCausedBy) {
        String firstLine = lines.next();
        if (removeCausedBy) {
            firstLine = firstLine.substring(CAUSED_BY_STRING_LENGTH);
        }
        final int colon = firstLine.indexOf(":");
        String throwableClassName;
        String message = null;
        if (colon > 1) {
            throwableClassName = firstLine.substring(0, colon);
            if (firstLine.length() > colon + 1) {
                final String rawMessage = firstLine.substring(colon + 1);
                final int encodedMessageStart = rawMessage.indexOf(ESCAPED_MESSAGE_PREFIX);
                if (encodedMessageStart >= 0
                        && rawMessage.substring(0, encodedMessageStart).trim().isEmpty()) {
                    message = unescapeMessage(
                            rawMessage.substring(encodedMessageStart + ESCAPED_MESSAGE_PREFIX.length()));
                } else {
                    message = rawMessage.trim();
                }
            }
        } else {
            throwableClassName = firstLine;
        }

        final List<StackTraceElement> stackTrace = new ArrayList<>();
        final StringBuilder messageContinuation = message == null ? null : new StringBuilder(message);
        Throwable cause = null;
        boolean stackTraceStarted = false;
        while (lines.hasNext()) {
            final String line = lines.next();

            if (line.startsWith("Caused by ")) {
                lines.previous();
                cause = convertString(lines, true);
                break;
            }

            if (line.startsWith("\tat ")) {
                stackTraceStarted = true;
                try {
                    stackTrace.add(StackTraceElementAttributeConverter.convertString(
                            line.substring(3).trim()));
                } catch (final RuntimeException ignored) {
                    // Ignore malformed stack trace lines to preserve the rest of the throwable.
                }
            } else if (!stackTraceStarted && messageContinuation != null) {
                messageContinuation.append('\n').append(line);
            }
        }

        if (messageContinuation != null && (message == null || messageContinuation.length() != message.length())) {
            message = messageContinuation.toString();
        }

        return this.getThrowable(
                throwableClassName, message, cause, stackTrace.toArray(EMPTY_STACK_TRACE_ELEMENT_ARRAY));
    }

    private Throwable getThrowable(
            final String throwableClassName,
            final String message,
            final Throwable cause,
            final StackTraceElement[] stackTrace) {
        try {
            final Class<?> loadedClass = Class.forName(throwableClassName, false, LoaderUtil.getClassLoader());
            if (!Throwable.class.isAssignableFrom(loadedClass)) {
                return null;
            }
            final Class<? extends Throwable> throwableClass = loadedClass.asSubclass(Throwable.class);

            Throwable throwable;
            if (message != null && cause != null) {
                throwable = this.getThrowable(throwableClass, message, cause);
                if (throwable == null) {
                    throwable = this.getThrowable(throwableClass, cause);
                    if (throwable == null) {
                        throwable = this.getThrowable(throwableClass, message);
                        if (throwable == null) {
                            throwable = this.getThrowable(throwableClass);
                            if (throwable != null) {
                                THROWABLE_MESSAGE.set(throwable, message);
                                THROWABLE_CAUSE.set(throwable, cause);
                            }
                        } else {
                            THROWABLE_CAUSE.set(throwable, cause);
                        }
                    } else {
                        THROWABLE_MESSAGE.set(throwable, message);
                    }
                }
            } else if (cause != null) {
                throwable = this.getThrowable(throwableClass, cause);
                if (throwable == null) {
                    throwable = this.getThrowable(throwableClass);
                    if (throwable != null) {
                        THROWABLE_CAUSE.set(throwable, cause);
                    }
                }
            } else if (message != null) {
                throwable = this.getThrowable(throwableClass, message);
                if (throwable == null) {
                    throwable = this.getThrowable(throwableClass);
                    if (throwable != null) {
                        THROWABLE_MESSAGE.set(throwable, cause);
                    }
                }
            } else {
                throwable = this.getThrowable(throwableClass);
            }

            if (throwable == null) {
                return null;
            }
            throwable.setStackTrace(stackTrace);
            return throwable;
        } catch (final Exception e) {
            return null;
        }
    }

    private Throwable getThrowable(
            final Class<? extends Throwable> throwableClass, final String message, final Throwable cause) {
        try {
            for (final Constructor<?> constructor : throwableClass.getConstructors()) {
                final Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length == 2) {
                    if (String.class == parameterTypes[0] && Throwable.class.isAssignableFrom(parameterTypes[1])) {
                        return (Throwable) constructor.newInstance(message, cause);
                    } else if (String.class == parameterTypes[1]
                            && Throwable.class.isAssignableFrom(parameterTypes[0])) {
                        return (Throwable) constructor.newInstance(cause, message);
                    }
                }
            }
            return null;
        } catch (final Exception e) {
            return null;
        }
    }

    private Throwable getThrowable(final Class<? extends Throwable> throwableClass, final Throwable cause) {
        try {
            for (final Constructor<?> constructor : throwableClass.getConstructors()) {
                final Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length == 1 && Throwable.class.isAssignableFrom(parameterTypes[0])) {
                    return (Throwable) constructor.newInstance(cause);
                }
            }
            return null;
        } catch (final Exception e) {
            return null;
        }
    }

    private Throwable getThrowable(final Class<? extends Throwable> throwableClass, final String message) {
        try {
            return throwableClass.getConstructor(String.class).newInstance(message);
        } catch (final Exception e) {
            return null;
        }
    }

    private Throwable getThrowable(final Class<? extends Throwable> throwableClass) {
        try {
            return LoaderUtil.newInstanceOf(throwableClass);
        } catch (final Exception e) {
            return null;
        }
    }
}
