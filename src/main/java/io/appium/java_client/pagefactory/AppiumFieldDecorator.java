/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.appium.java_client.pagefactory;

import com.google.common.collect.ImmutableList;
import io.appium.java_client.internal.CapabilityHelpers;
import io.appium.java_client.pagefactory.bys.ContentType;
import io.appium.java_client.pagefactory.locator.CacheableLocator;
import io.appium.java_client.remote.MobileCapabilityType;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.RemoteWebElement;
import org.openqa.selenium.support.pagefactory.DefaultFieldDecorator;
import org.openqa.selenium.support.pagefactory.ElementLocator;
import org.openqa.selenium.support.pagefactory.FieldDecorator;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.appium.java_client.pagefactory.utils.ProxyFactory.getEnhancedProxy;
import static io.appium.java_client.pagefactory.utils.WebDriverUnpackUtility.unpackWebDriverFromSearchContext;
import static java.time.Duration.ofSeconds;

/**
 * Default decorator for use with PageFactory. Will decorate 1) all of the
 * WebElement fields and 2) List of WebElement that have
 * {@literal @AndroidFindBy}, {@literal @AndroidFindBys}, or
 * {@literal @iOSFindBy/@iOSFindBys} annotation with a proxy that locates the
 * elements using the passed in ElementLocatorFactory.
 * Please pay attention: fields of {@link WebElement} or {@link RemoteWebElement}
 * to use with this decorator
 */
public class AppiumFieldDecorator implements FieldDecorator {

    private static final List<Class<? extends WebElement>> availableElementClasses = ImmutableList.of(WebElement.class,
            RemoteWebElement.class);
    public static final Duration DEFAULT_WAITING_TIMEOUT = ofSeconds(1);
    private final WebDriver webDriver;
    private final DefaultFieldDecorator defaultElementFieldDecoracor;
    private final AppiumElementLocatorFactory widgetLocatorFactory;
    private final String platform;
    private final String automation;
    private final Duration duration;

    /**
     * Creates field decorator based on {@link SearchContext} and timeout {@code duration}.
     *
     * @param context  is an instance of {@link SearchContext}
     *                 It may be the instance of {@link WebDriver} or {@link WebElement} or
     *                 {@link Widget} or some other user's extension/implementation.
     * @param duration is a desired duration of the waiting for an element presence.
     */
    public AppiumFieldDecorator(SearchContext context, Duration duration) {
        this.webDriver = unpackWebDriverFromSearchContext(context);

        if (this.webDriver instanceof HasCapabilities) {
            Capabilities caps = ((HasCapabilities) this.webDriver).getCapabilities();
            this.platform = CapabilityHelpers.getCapability(caps, CapabilityType.PLATFORM_NAME, String.class);
            this.automation = CapabilityHelpers.getCapability(caps, MobileCapabilityType.AUTOMATION_NAME, String.class);
        } else {
            this.platform = null;
            this.automation = null;
        }

        this.duration = duration;

        defaultElementFieldDecoracor = new DefaultFieldDecorator(
                new AppiumElementLocatorFactory(context, duration, new DefaultElementByBuilder(platform, automation))
        ) {
            @Override
            protected WebElement proxyForLocator(ClassLoader ignored, ElementLocator locator) {
                return proxyForAnElement(locator);
            }

            @Override
            protected List<WebElement> proxyForListLocator(ClassLoader ignored, ElementLocator locator) {
                ElementListInterceptor elementInterceptor = new ElementListInterceptor(locator);
                //noinspection unchecked
                return getEnhancedProxy(ArrayList.class, elementInterceptor);
            }

            @Override
            protected boolean isDecoratableList(Field field) {
                if (!List.class.isAssignableFrom(field.getType())) {
                    return false;
                }

                Type genericType = field.getGenericType();
                if (!(genericType instanceof ParameterizedType)) {
                    return false;
                }

                Type listType = ((ParameterizedType) genericType).getActualTypeArguments()[0];
                List<Type> bounds = (listType instanceof TypeVariable)
                        ? Arrays.asList(((TypeVariable<?>) listType).getBounds())
                        : Collections.emptyList();
                return availableElementClasses.stream()
                        .anyMatch((webElClass) -> webElClass.equals(listType) || bounds.contains(webElClass));
            }
        };

        widgetLocatorFactory = new AppiumElementLocatorFactory(
                context, duration, new WidgetByBuilder(platform, automation)
        );
    }

    public AppiumFieldDecorator(SearchContext context) {
        this(context, DEFAULT_WAITING_TIMEOUT);
    }

    /**
     * Decorated page object {@code field}.
     *
     * @param ignored class loader is ignored by current implementation
     * @param field   is {@link Field} of page object which is supposed to be decorated.
     * @return a field value or null.
     */
    public Object decorate(ClassLoader ignored, Field field) {
        Object result = defaultElementFieldDecoracor.decorate(ignored, field);
        return result == null ? decorateWidget(field) : result;
    }

    @Nullable
    private Object decorateWidget(Field field) {
        Class<?> type = field.getType();
        if (!Widget.class.isAssignableFrom(type) && !List.class.isAssignableFrom(type)) {
            return null;
        }

        Class<? extends Widget> widgetType;
        boolean isAlist = false;
        if (List.class.isAssignableFrom(type)) {
            isAlist = true;
            Type genericType = field.getGenericType();
            if (!(genericType instanceof ParameterizedType)) {
                return null;
            }

            Type listType = ((ParameterizedType) genericType).getActualTypeArguments()[0];

            if (ParameterizedType.class.isAssignableFrom(listType.getClass())) {
                listType = ((ParameterizedType) listType).getRawType();
            }

            if (listType instanceof Class) {
                if (!Widget.class.isAssignableFrom((Class<?>) listType)) {
                    return null;
                }
                //noinspection unchecked
                widgetType = (Class<? extends Widget>) listType;
            } else {
                return null;
            }

        } else {
            //noinspection unchecked
            widgetType = (Class<? extends Widget>) field.getType();
        }

        CacheableLocator locator = widgetLocatorFactory.createLocator(field);
        Map<ContentType, Constructor<? extends Widget>> map = OverrideWidgetReader.read(widgetType, field, platform);

        if (isAlist) {
            return getEnhancedProxy(
                    ArrayList.class,
                    new WidgetListInterceptor(locator, webDriver, map, widgetType, duration)
            );
        }

        Constructor<? extends Widget> constructor = WidgetConstructorUtil.findConvenientConstructor(widgetType);
        return getEnhancedProxy(
                widgetType,
                new Class[]{constructor.getParameterTypes()[0]},
                new Object[]{proxyForAnElement(locator)},
                new WidgetInterceptor(locator, webDriver, null, map, duration)
        );
    }

    private WebElement proxyForAnElement(ElementLocator locator) {
        ElementInterceptor elementInterceptor = new ElementInterceptor(locator, webDriver);
        return getEnhancedProxy(RemoteWebElement.class, elementInterceptor);
    }
}
