package org.eclipse.jetty.logging.impl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import junit.framework.TestCase;

import org.eclipse.jetty.logging.MavenTestingUtils;
import org.slf4j.Logger;

public class ConfiguredLoggerTest extends TestCase
{
    private void assertAppenders(CentralLoggerConfig logger, Class<?>... clazzes)
    {
        assertNotNull("Appenders should not be null",logger.getAppenders());
        assertTrue("Should have appenders",logger.getAppenders().size() >= 1);

        List<String> expectedAppenders = new ArrayList<String>();
        List<String> actualAppenders = new ArrayList<String>();

        for (Class<?> clazz : clazzes)
        {
            expectedAppenders.add(clazz.getName());
        }

        for (Appender appender : logger.getAppenders())
        {
            actualAppenders.add(appender.getClass().getName());
        }

        // Sort
        Collections.sort(expectedAppenders);
        Collections.sort(actualAppenders);

        // Same Size?
        if (expectedAppenders.size() != actualAppenders.size())
        {
            System.out.println("/* Actual */");
            for (String name : actualAppenders)
            {
                System.out.println(name);
            }
            System.out.println("/* Expected */");
            for (String name : expectedAppenders)
            {
                System.out.println(name);
            }
            assertEquals("Appender count",expectedAppenders.size(),actualAppenders.size());
        }

        // Same Content?
        for (int i = 0, n = expectedAppenders.size(); i < n; i++)
        {
            assertEquals("Appender[" + i + "]",expectedAppenders.get(i),actualAppenders.get(i));
        }
    }

    private void assertSeverityLevel(CentralLoggerConfig logger, Severity severity)
    {
        assertEquals("Severity",severity,logger.getLevel());
    }

    public void testRootDebug() throws Exception
    {
        Properties props = new Properties();
        props.setProperty("root.level","DEBUG");

        CentralLoggerConfig root = CentralLoggerConfig.load(props);
        assertNotNull("Root Logger should not be null",root);
        assertSeverityLevel(root,Severity.DEBUG);
        assertAppenders(root,ConsoleAppender.class);
    }

    public void testRootTrace() throws Exception
    {
        Properties props = new Properties();
        props.setProperty("root.level","TRACE");

        CentralLoggerConfig root = CentralLoggerConfig.load(props);
        assertNotNull("Root Logger should not be null",root);
        assertSeverityLevel(root,Severity.TRACE);
        assertAppenders(root,ConsoleAppender.class);
    }

    public void testRootWarn() throws Exception
    {
        Properties props = new Properties();
        props.setProperty("root.level","WARN");

        CentralLoggerConfig root = CentralLoggerConfig.load(props);
        assertNotNull("Root Logger should not be null",root);
        assertSeverityLevel(root,Severity.WARN);
        assertAppenders(root,ConsoleAppender.class);
    }

    public void testSimpleConfig() throws Exception
    {
        Properties props = new Properties();
        props.setProperty("root.level","DEBUG");

        CentralLoggerConfig root = CentralLoggerConfig.load(props);
        assertNotNull("Root Logger should not be null",root);
        assertSeverityLevel(root,Severity.DEBUG);
        assertAppenders(root,ConsoleAppender.class);
    }

    public void testCapturedAppender() throws Exception
    {
        Properties props = new Properties();
        props.setProperty("root.level","DEBUG");
        props.setProperty("root.appenders","test");
        props.setProperty("appender.test.class",TestAppender.class.getName());

        CentralLoggerConfig root = CentralLoggerConfig.load(props);
        assertNotNull("Root Logger should not be null",root);
        assertSeverityLevel(root,Severity.DEBUG);
        assertAppenders(root,TestAppender.class);
    }

    public void testRollingFileAppender() throws Exception
    {
        Properties props = new Properties();

        File testLoggingDir = new File(MavenTestingUtils.getTargetTestingDir(this),"logs");
        testLoggingDir.mkdirs();
        File logFile = new File(testLoggingDir,"rolling.log");

        props.setProperty("root.level","DEBUG");
        props.setProperty("root.appenders","roll");
        props.setProperty("appender.roll.class",RollingFileAppender.class.getName());
        props.setProperty("appender.roll.filename",logFile.getAbsolutePath());
        props.setProperty("appender.roll.append","true");
        props.setProperty("appender.roll.retainDays","120");
        props.setProperty("appender.roll.zone","GMT");
        props.setProperty("appender.roll.dateFormat","yyyy-MM-dd");
        props.setProperty("appender.roll.backupFormat","HH-mm-ss.SSS");

        CentralLoggerConfig root = CentralLoggerConfig.load(props);
        assertNotNull("Root Logger should not be null",root);
        assertSeverityLevel(root,Severity.DEBUG);
        assertAppenders(root,RollingFileAppender.class);

        RollingFileAppender actualAppender = (RollingFileAppender)root.getAppenders().get(0);
        assertEquals("RollingFileAppender.filename",logFile.getAbsolutePath(),actualAppender.getFilename());
        assertEquals("RollingFileAppender.append",true,actualAppender.isAppend());
        assertEquals("RollingFileAppender.retainDays",120,actualAppender.getRetainDays());
        assertEquals("RollingFileAppender.zone","GMT",actualAppender.getZone().getID());
        assertEquals("RollingFileAppender.dateFormat","yyyy-MM-dd",actualAppender.getDateFormat());
        assertEquals("RollingFileAppender.backupFormat","HH-mm-ss.SSS",actualAppender.getBackupFormat());
    }

    public void testGetConfiguredLogger() throws IOException
    {
        Properties props = new Properties();
        props.setProperty("root.level","DEBUG");
        props.setProperty("root.appenders","console");
        props.setProperty("logger.org.eclipse.jetty.logging.level","WARN");
        props.setProperty("logger.org.eclipse.jetty.logging.appenders","test");
        props.setProperty("appender.test.class",TestAppender.class.getName());
        props.setProperty("appender.console.class",ConsoleAppender.class.getName());

        CentralLoggerConfig root = CentralLoggerConfig.load(props);
        assertNotNull("Root Logger should not be null",root);
        assertEquals("Root Logger.name",Logger.ROOT_LOGGER_NAME,root.getName());
        assertSeverityLevel(root,Severity.DEBUG);
        assertAppenders(root,ConsoleAppender.class);

        CentralLoggerConfig jettyLogger = root.getConfiguredLogger("org.eclipse.jetty");
        assertNotNull("Jetty Logger should not be null",jettyLogger);
        assertEquals("Jetty Logger.name","org.eclipse.jetty",jettyLogger.getName());
        assertSeverityLevel(jettyLogger,Severity.DEBUG);
        assertAppenders(jettyLogger,ConsoleAppender.class);

        CentralLoggerConfig implLogger = root.getConfiguredLogger("org.eclipse.jetty.logging.impl");
        assertNotNull("Jetty Logging Impl Logger should not be null",implLogger);
        assertEquals("Jetty Logging Impl Logger.name","org.eclipse.jetty.logging.impl",implLogger.getName());
        assertSeverityLevel(implLogger,Severity.WARN);
        assertAppenders(implLogger,ConsoleAppender.class,TestAppender.class);
    }
}
