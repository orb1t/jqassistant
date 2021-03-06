package com.buschmais.jqassistant.plugin.junit4.impl.scanner;

import static com.buschmais.jqassistant.plugin.junit4.api.scanner.JunitScope.TESTREPORTS;

import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Iterator;
import java.util.Locale;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.Scope;
import com.buschmais.jqassistant.core.store.api.model.FileDescriptor;
import com.buschmais.jqassistant.plugin.common.api.scanner.filesystem.VirtualFile;
import com.buschmais.jqassistant.plugin.common.impl.scanner.AbstractScannerPlugin;
import com.buschmais.jqassistant.plugin.junit4.api.model.TestCaseDescriptor;
import com.buschmais.jqassistant.plugin.junit4.api.model.TestSuiteDescriptor;

public class TestReportScannerPlugin extends AbstractScannerPlugin<VirtualFile> {

    private final NumberFormat timeFormat = NumberFormat.getInstance(Locale.US);

    @Override
    protected void initialize() {
    }

    @Override
    public Class<? super VirtualFile> getType() {
        return VirtualFile.class;
    }

    @Override
    public boolean accepts(VirtualFile item, String path, Scope scope) throws IOException {
        return TESTREPORTS.equals(scope) && path.matches(".*TEST-.*\\.xml");
    }

    @Override
    public FileDescriptor scan(VirtualFile item, String path, Scope scope, Scanner scanner) throws IOException {
        XMLInputFactory inputFactory = XMLInputFactory.newInstance();
        XMLEventReader reader;
        try (InputStream stream = item.createStream()) {
            reader = inputFactory.createXMLEventReader(stream);
            TestSuiteDescriptor testSuiteDescriptor = null;
            TestCaseDescriptor testCaseDescriptor = null;
            while (reader.hasNext()) {
                XMLEvent event = (XMLEvent) reader.next();
                if (event.isStartElement()) {
                    StartElement element = event.asStartElement();
                    String elementName = element.getName().getLocalPart();
                    @SuppressWarnings("unchecked")
                    Iterator<Attribute> attributes = element.getAttributes();
                    switch (elementName) {
                    case "testsuite":
                        testSuiteDescriptor = scanner.getContext().getStore().create(TestSuiteDescriptor.class);
                        while (attributes.hasNext()) {
                            Attribute attribute = attributes.next();
                            String attributeName = attribute.getName().getLocalPart();
                            String value = attribute.getValue();
                            switch (attributeName) {
                            case "name":
                                testSuiteDescriptor.setName(value);
                                break;
                            case "time":
                                testSuiteDescriptor.setTime(parseTime(value));
                                break;
                            case "tests":
                                testSuiteDescriptor.setTests(Integer.parseInt(value));
                                break;
                            case "failures":
                                testSuiteDescriptor.setFailures(Integer.parseInt(value));
                                break;
                            case "errors":
                                testSuiteDescriptor.setErrors(Integer.parseInt(value));
                                break;
                            case "skipped":
                                testSuiteDescriptor.setSkipped(Integer.parseInt(value));
                                break;
                            }
                        }
                        break;
                    case "testcase":
                        testCaseDescriptor = scanner.getContext().getStore().create(TestCaseDescriptor.class);
                        testCaseDescriptor.setResult(TestCaseDescriptor.Result.SUCCESS);
                        testSuiteDescriptor.getTestCases().add(testCaseDescriptor);
                        while (attributes.hasNext()) {
                            Attribute attribute = (Attribute) attributes.next();
                            String attributeName = attribute.getName().getLocalPart();
                            String value = attribute.getValue();
                            switch (attributeName) {
                            case "name":
                                testCaseDescriptor.setName(value);
                                break;
                            case "time":
                                testCaseDescriptor.setTime(parseTime(value));
                                break;
                            case "classname":
                                testCaseDescriptor.setClassName(value);
                                break;
                            }
                        }
                        break;
                    case "failure":
                        testCaseDescriptor.setResult(TestCaseDescriptor.Result.FAILURE);
                        break;
                    case "error":
                        testCaseDescriptor.setResult(TestCaseDescriptor.Result.ERROR);
                        break;
                    case "skipped":
                        testCaseDescriptor.setResult(TestCaseDescriptor.Result.SKIPPED);
                        break;
                    }
                }
            }
            return testSuiteDescriptor;
        } catch (XMLStreamException e) {
            throw new IOException("Cannot read XML document.", e);
        }
    }

    private float parseTime(String value) throws IOException {
        try {
            return timeFormat.parse(value).floatValue();
        } catch (ParseException e) {
            throw new IOException("Cannot parse time.", e);
        }
    }
}
