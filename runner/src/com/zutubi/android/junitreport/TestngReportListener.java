/*
 * Copyright (C) 2010-2011 Zutubi Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zutubi.android.junitreport;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.util.Xml;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestListener;

import org.imaginea.botbot.common.DataDrivenTestCase;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;

/**
 * Custom test listener that outputs test results to XML files. The files use a
 * similar format to the Ant JUnit task XML formatter, with a few of caveats:
 * <ul>
 * <li>
 * By default, multiple suites are all placed in a single file under a root
 * &lt;testsuites&gt; element. In multiFile mode a separate file is created for
 * each suite, which may be more compatible with existing tools.</li>
 * <li>
 * Redundant information about the number of nested cases within a suite is
 * omitted.</li>
 * <li>
 * Durations are omitted from suites.</li>
 * <li>
 * Neither standard output nor system properties are included.</li>
 * </ul>
 * The differences mainly revolve around making this reporting as lightweight as
 * possible. The report is streamed as the tests run, making it impossible to,
 * e.g. include the case count in a &lt;testsuite&gt; element.
 */
public class TestngReportListener implements TestListener {
	private static final String LOG_TAG = "JUnitReportListener";

	private static final String ENCODING_UTF_8 = "utf-8";

	private static final String TAG_SUITES = "testng-results";
	private static final String TAG_SUITE = "suite";
	private static final String TAG_TEST = "test";
	private static final String TAG_GROUPS = "groups";
	private static final String TAG_CLASS = "class";
	private static final String TAG_CASE = "test-method";
	private static final String TAG_ERROR = "error";
	private static final String TAG_FAILURE = "failure";
	private static final String TAG_EXCEPTION = "exception";
	private static final String TAG_MESSAGE = "message";

	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_CLASS = "classname";
	private static final String ATTRIBUTE_TYPE = "type";
	private static final String ATTRIBUTE_MESSAGE = "message";
	private static final String ATTRIBUTE_TIME = "duration-ms";
	private static final String ATTRIBUTE_STATUS = "status";
	private static final String ATTRIBUTE_START_TIME = "started-at";
	private static final String ATTRIBUTE_SIGNATURE = "signature";
	private static final String ATTRIBUTE_FINISHED_AT = "finished-at";
	private static final String ATTRIBUTE_CONFIG = "is-config";
	private static final String ATTRIBUTE_SKIPPED = "skipped";
	private static final String ATTRIBUTE_FAILED = "failed";
	private static final String ATTRIBUTE_TOTAL = "total";
	private static final String ATTRIBUTE_PASSED = "passed";

	// With thanks to
	// org.apache.tools.ant.taskdefs.optional.junit.JUnitTestRunner.
	// Trimmed some entries, added others for Android.
	private static final String[] DEFAULT_TRACE_FILTERS = new String[] {
			"junit.framework.TestCase", "junit.framework.TestResult",
			"junit.framework.TestSuite",
			"junit.framework.Assert.", // don't filter AssertionFailure
			"java.lang.reflect.Method.invoke(", "sun.reflect.",
			// JUnit 4 support:
			"org.junit.", "junit.framework.JUnit4TestAdapter", " more",
			// Added for Android
			"android.test.", "android.app.Instrumentation",
			"java.lang.reflect.Method.invokeNative", };

	private Context mContext;
	private Context mTargetContext;
	private String mReportFile;
	private String mReportDir;
	private boolean mFilterTraces;
	private boolean mMultiFile;
	private FileOutputStream mOutputStream;
	private XmlSerializer mSerializer;
	private String mCurrentSuite;
	private HashMap<String, HashMap<String, TestKeeper>> suiteMap = new HashMap<String, HashMap<String, TestKeeper>>();
	private HashMap<String, SuiteKeeper> suiteDetails = new HashMap<String, SuiteKeeper>();
	private SuiteKeeper currentSuite=new SuiteKeeper();
	// simple time tracking
	private boolean mTimeAlreadyWritten = false;
	private long mTestStartTime;
	private int errorCount = 0;
	private int failureCount = 0;
	private int total=0;

	/**
	 * Creates a new listener.
	 * 
	 * @param context
	 *            context of the test application
	 * @param targetContext
	 *            context of the application under test
	 * @param reportFile
	 *            name of the report file(s) to create
	 * @param reportDir
	 *            path of the directory under which to write files (may be null
	 *            in which case files are written under the context using
	 *            {@link Context#openFileOutput(String, int)}).
	 * @param filterTraces
	 *            if true, stack traces will have common noise (e.g. framework
	 *            methods) omitted for clarity
	 * @param multiFile
	 *            if true, use a separate file for each test suite
	 */
	public TestngReportListener(Context context, Context targetContext,
			String reportFile, String reportDir, boolean filterTraces,
			boolean multiFile) {
		this.mContext = context;
		this.mTargetContext = targetContext;
		this.mReportFile = reportFile;
		this.mReportDir = reportDir;
		this.mFilterTraces = filterTraces;
		this.mMultiFile = multiFile;
	}

	@Override
	public void startTest(Test test) {
		if (test instanceof TestCase) {
			total++;
			TestCase testCase = (TestCase) test;
			TestKeeper testKeeper = new TestKeeper();
			String testName = getTestName(test);
			testKeeper.setTestname(testName);
			mTimeAlreadyWritten = false;
			mTestStartTime = System.currentTimeMillis();
			testKeeper.setStartTime(mTestStartTime);
			currentSuite.setStartTime(System.currentTimeMillis());
			testKeeper.setTest(testCase);
			checkSuiteDetails(test);
			addToSuite(test, testKeeper);
		}
	}

	private void addToSuite(Test test, TestKeeper testKeeper) {
		TestCase testcase = (TestCase) test;
		String suiteName = testcase.getClass().getName();
		HashMap<String, TestKeeper> suiteTests;
		if (suiteMap.containsKey(suiteName)) {
			suiteTests = suiteMap.get(suiteName);
		} else {
			suiteTests = new HashMap<String, TestKeeper>();
		}
		if (suiteDetails.containsKey(suiteName)) {
			SuiteKeeper sKeeper = suiteDetails.get(suiteName);
			currentSuite.setEndTime(sKeeper.getEndTime());
			sKeeper.setEndTime(testKeeper.getEndTime());
			suiteDetails.put(suiteName, sKeeper);
		}
		suiteTests.put(getTestName(test), testKeeper);
		suiteMap.put(suiteName, suiteTests);
	}

	private void checkSuiteDetails(Test test) {
		TestCase testcase = (TestCase) test;
		String suiteName = testcase.getClass().getName();
		if (!suiteDetails.containsKey(suiteName)) {
			SuiteKeeper sKeeper = new SuiteKeeper();
			sKeeper.setStartTime(System.currentTimeMillis());
			suiteDetails.put(suiteName, sKeeper);
		}
	}

	private void updateSuiteDetails(Test test, String tag) {
		checkSuiteDetails(test);
		TestCase testcase = (TestCase) test;
		String suiteName = testcase.getClass().getName();
		SuiteKeeper suiteKeeper = suiteDetails.get(suiteName);
		if (tag.contentEquals(TAG_ERROR)) {
			suiteKeeper.setErrorCount();
		} else if (tag.contentEquals(TAG_FAILURE)) {
			suiteKeeper.setFailureCount();
		}
	}

	private String getTestName(Test test) {
		String testName = "";
		if (test instanceof TestCase) {
			TestCase testCase = (TestCase) test;
			if (DataDrivenTestCase.class.isAssignableFrom(test.getClass())) {
				testName = ((DataDrivenTestCase) testCase).getCustomTestName();
			} else {

				testName = testCase.getName();
			}
		}
		return testName;
	}

	private boolean deleteDir(File dir) {
		if (dir.isDirectory()) {
			String[] children = dir.list();
			for (int i = 0; i < children.length; i++) {
				boolean success = deleteDir(new File(dir, children[i]));
				if (!success) {
					return false;
				}
			}
		}

		// The directory is now empty so delete it
		return dir.delete();
	}

	private void openIfRequired() throws IOException {
		String state = Environment.getExternalStorageState();
		if (mSerializer == null) {
			String fileName = mReportFile;
			if (mMultiFile) {
				fileName = fileName.replace("$(suite)", "test");
			}
			if (mReportDir == null) {
				if (Environment.MEDIA_MOUNTED.equals(state)
						&& !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
					File f = mContext.getExternalFilesDir("testng");
					mOutputStream = new FileOutputStream(new File(f, fileName));
				} else {
					mOutputStream = mTargetContext.openFileOutput(fileName, 0);
				}
			} else {
				mOutputStream = new FileOutputStream(new File(mReportDir,
						fileName));
			}

			mSerializer = Xml.newSerializer();
			mSerializer.setOutput(mOutputStream, ENCODING_UTF_8);
			mSerializer.startDocument(ENCODING_UTF_8, true);
			if (!mMultiFile) {
				mSerializer.startTag("", TAG_SUITES);
			}
		}
	}

	@Override
	public void addError(Test test, Throwable error) {
		errorCount++;
		addProblem(test, TAG_ERROR, error);
	}

	@Override
	public void addFailure(Test test, AssertionFailedError error) {
		failureCount++;
		addProblem(test, TAG_FAILURE, error);
	}

	private void addProblem(Test test, String tag, Throwable error) {
		recordTestTime(test);
		TestKeeper testKeeper = getTestKeeper(test);
		testKeeper.setError(error);
		testKeeper.setTag(tag);
		if(TAG_ERROR.contentEquals(tag)){
			testKeeper.setStatus("SKIP");
		}else{
			testKeeper.setStatus("FAIL");
		}
		addToSuite(test, testKeeper);
		updateSuiteDetails(test, tag);
	}

	private TestKeeper getTestKeeper(Test test) {
		TestCase testcase = (TestCase) test;
		String suiteName = testcase.getClass().getName();
		HashMap<String, TestKeeper> suiteTests;
		if (suiteMap.containsKey(suiteName)) {
			suiteTests = suiteMap.get(suiteName);
			if (suiteTests.containsKey(getTestName(test))) {
				return suiteTests.get(getTestName(test));
			}
		}
		TestKeeper testKeeper = new TestKeeper();
		String testName = getTestName(test);
		testKeeper.setTestname(testName);
		mTestStartTime = System.currentTimeMillis();
		testKeeper.setStartTime(mTestStartTime);
		testKeeper.setTest(testcase);
		return testKeeper;
	}

	private void recordTestTime(Test test) {
		if (!mTimeAlreadyWritten) {
			mTimeAlreadyWritten = true;
			TestKeeper testKeeper = getTestKeeper(test);
			testKeeper.setEndTime(System.currentTimeMillis());
			addToSuite(test, testKeeper);
		}
	}

	@Override
	public void endTest(Test test) {
		if (test instanceof TestCase) {
			recordTestTime(test);
		}
	}

	private void addAttribute(String tag, String value) throws IOException {
		mSerializer.attribute("", tag, value);
	}

	public void close() {
		Iterator<String> suiteIterator = suiteMap.keySet().iterator();
		String state = Environment.getExternalStorageState();
		if (Environment.MEDIA_MOUNTED.equals(state)
				&& !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
			File f = mContext.getExternalFilesDir("testng");
			if (f.exists()) {
				deleteDir(f);
				f.mkdirs();
			}
		}
		try {
			openIfRequired();

			addAttribute(ATTRIBUTE_SKIPPED, String.valueOf(errorCount));
			addAttribute(ATTRIBUTE_FAILED, String.valueOf(failureCount));
			addAttribute(ATTRIBUTE_TOTAL, String.valueOf(total));
			addAttribute(ATTRIBUTE_PASSED, String.valueOf(total-errorCount-failureCount));
			mSerializer.startTag("", "reporter-output");
			mSerializer.endTag("", "reporter-output");
			mSerializer.startTag("", TAG_SUITE);
			addAttribute(ATTRIBUTE_NAME, "Bot-bot suite");
			String suiteTime = String
					.format(Locale.ENGLISH, "%.3f",
							(currentSuite.getEndTime() - currentSuite
									.getStartTime()) / 1000.);
			addAttribute(ATTRIBUTE_TIME, suiteTime);
			addAttribute(ATTRIBUTE_START_TIME, getTestngFormattedDate(currentSuite.getStartTime()));
			addAttribute(ATTRIBUTE_FINISHED_AT, getTestngFormattedDate(currentSuite.getEndTime()));
			mSerializer.startTag("", TAG_GROUPS);
			mSerializer.endTag("", TAG_GROUPS);
			mSerializer.startTag("", TAG_TEST);
			addAttribute(ATTRIBUTE_NAME, "Bot-bot suite");
			addAttribute(ATTRIBUTE_TIME, suiteTime);
			addAttribute(ATTRIBUTE_START_TIME, getTestngFormattedDate(currentSuite.getStartTime()));
			addAttribute(ATTRIBUTE_FINISHED_AT, getTestngFormattedDate(currentSuite.getEndTime()));
			while (suiteIterator.hasNext()) {
				String suiteName = suiteIterator.next();
				HashMap<String, TestKeeper> suiteTests = suiteMap
						.get(suiteName);
				mSerializer.startTag("", TAG_CLASS);
				addAttribute(ATTRIBUTE_NAME, suiteName);
				Iterator<String> testIterator = suiteTests.keySet().iterator();
				while (testIterator.hasNext()) {
					String testName = testIterator.next();
					TestKeeper testKeeper = suiteTests.get(testName);
					mSerializer.startTag("", TAG_CASE);
					addAttribute(ATTRIBUTE_STATUS, testKeeper.getStatus());
					addAttribute(ATTRIBUTE_SIGNATURE, testName);
					addAttribute(ATTRIBUTE_NAME, testName);
					String timeTaken = String.format(Locale.ENGLISH, "%.3f",
							(testKeeper.getEndTime() - testKeeper
									.getStartTime()) / 1000.);
					addAttribute(ATTRIBUTE_TIME, timeTaken);
					addAttribute(ATTRIBUTE_START_TIME, getTestngFormattedDate(testKeeper.getStartTime()));
					addAttribute(ATTRIBUTE_FINISHED_AT, getTestngFormattedDate(testKeeper.getEndTime()));
					if (testKeeper.isFailed()) {
						Throwable error = testKeeper.getError();
						
						mSerializer.startTag("", TAG_EXCEPTION);
						addAttribute(ATTRIBUTE_CLASS, error.getClass().getName());
						mSerializer.startTag("", TAG_MESSAGE);
						mSerializer.text(safeMessage(error));
						mSerializer.endTag("", TAG_MESSAGE);
						mSerializer.startTag("", "full-stacktrace");
						StringWriter w = new StringWriter();
						error.printStackTrace(mFilterTraces ? new FilteringWriterTest(
								w) : new PrintWriter(w));
						mSerializer.text(w.toString());
						mSerializer.endTag("", "full-stacktrace");
						mSerializer.endTag("", TAG_EXCEPTION);
					}
					mSerializer.endTag("", TAG_CASE);

				}	
				mSerializer.endTag("", TAG_CLASS);
			}
			mSerializer.endTag("", TAG_TEST);
			mSerializer.endTag("", TAG_SUITE);
			mSerializer.endTag("", TAG_SUITES);
		} catch (IOException e) {
			Log.e(LOG_TAG, safeMessage(e));
		}

		closeSuite();
	}

	/**
	 * Releases all resources associated with this listener. Must be called when
	 * the listener is finished with.
	 */
	public void closeSuite() {
		if (mSerializer != null) {
			try {
				mSerializer.endDocument();
				mSerializer = null;
			} catch (IOException e) {
				Log.e(LOG_TAG, safeMessage(e));
			}
		}

		if (mOutputStream != null) {
			try {
				mOutputStream.close();
				mOutputStream = null;
			} catch (IOException e) {
				Log.e(LOG_TAG, safeMessage(e));
			}
		}
	}

	private String getTestngFormattedDate(long timeInMiliSecs) {
		DateFormat df = new SimpleDateFormat("yyyy-MM-DD'T'HH:mm:ss");
		Calendar calendar = Calendar.getInstance();
		calendar.setTimeInMillis(timeInMiliSecs);
		String dateTime = df.format(calendar.getTime());
		return dateTime;
	}

	private String safeMessage(Throwable error) {
		String message = error.getMessage();
		return error.getClass().getName() + ": "
				+ (message == null ? "<null>" : message);
	}

	/**
	 * Wrapper around a print writer that filters out common noise from stack
	 * traces, making it easier to see the actual failure.
	 */
	private static class FilteringWriterTest extends PrintWriter {
		public FilteringWriterTest(Writer out) {
			super(out);
		}

		@Override
		public void println(String s) {
			for (String filtered : DEFAULT_TRACE_FILTERS) {
				if (s.contains(filtered)) {
					return;
				}
			}

			super.println(s);
		}
	}

	private class SuiteKeeper {
		private int failureCount = 0;
		private int errorCount = 0;
		private long startTime = 0;
		private long endTime = 0;

		public long getStartTime() {
			return startTime;
		}

		public void setStartTime(long startTime) {
			if (this.startTime > startTime || this.startTime==0) {
				this.startTime = startTime;
			}
		}

		public long getEndTime() {
			return endTime;
		}

		public void setEndTime(long endTime) {
			if (this.endTime < endTime) {
				this.endTime = endTime;
			}
		}

		public String getFailureCount() {
			return String.valueOf(failureCount);
		}

		public void setFailureCount() {
			failureCount++;
		}

		public String getErrorCount() {
			return String.valueOf(errorCount);
		}

		public void setErrorCount() {
			errorCount++;
		}

	}
}
