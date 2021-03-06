/*
 * Copyright 2012-2015 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

package com.comcast.money.aspectj

import com.comcast.money.annotations.{ Timed, Traced, TracedData }
import com.comcast.money.core.internal.MDCSupport
import com.comcast.money.core.{ Tracer, Money, SpecHelpers }
import org.aspectj.lang.ProceedingJoinPoint
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.mock.MockitoSugar

class TraceAspectSpec extends WordSpec
    with GivenWhenThen with OneInstancePerTest with BeforeAndAfterEach with Matchers with MockitoSugar with Eventually
    with SpecHelpers {

  @Traced("methodWithArguments")
  def methodWithArguments(@TracedData("foo") foo: String, @TracedData("CUSTOM_NAME") bar: String) = {
    Thread.sleep(50)
  }

  @Traced("methodWithoutArguments")
  def methodWithoutArguments() = {
    Thread.sleep(50)
  }

  @Traced("methodThrowingException")
  def methodThrowingException() = {
    Thread.sleep(50)
    throw new RuntimeException("test failure")
  }

  @Traced("methodThrowingExceptionWithNoMessage")
  def methodThrowingExceptionWithNoMessage() = {
    Thread.sleep(50)
    throw new RuntimeException()
  }

  @Traced("methodWithArgumentsPropagated")
  def methodWithArgumentsPropagated(
    @TracedData(value = "PROPAGATE", propagate = true) foo: String,
    @TracedData("CUSTOM_NAME") bar: String
  ) = {
    Thread.sleep(50)
    methodWithoutArguments()
  }

  @Traced(
    value = "methodWithIgnoredException",
    ignoredExceptions = Array(classOf[IllegalArgumentException])
  )
  def methodWithIgnoredException() = {
    throw new IllegalArgumentException("ignored")
  }

  @Traced(
    value = "methodWithNonMatchingIgnoredException",
    ignoredExceptions = Array(classOf[IllegalArgumentException])
  )
  def methodWithNonMatchingIgnoredException() = {
    throw new RuntimeException("not-ignored")
  }

  @Timed("methodWithTiming")
  def methodWithTiming() = {
    Thread.sleep(50)
  }

  val mockMdcSupport = mock[MDCSupport]
  object testTraceAspect extends TraceAspect {
    override val mdcSupport: MDCSupport = mockMdcSupport
  }

  override def beforeEach() = {
    reset(mockMdcSupport)
  }

  "TraceAspect" when {
    "advising methods by tracing them" should {
      "handle methods that have no arguments" in {
        Given("a method that has the tracing annotation but has no arguments")
        When("the method is invoked")
        methodWithoutArguments()

        Then("the method execution is traced")
        expectLogMessageContaining("methodWithoutArguments")

        And("the result of success is captured")
        expectLogMessageContaining("span-success=true")
      }
      "complete the trace for methods that throw exceptions" in {
        Given("a method that throws an exception")

        When("the method is invoked")
        a[RuntimeException] should be thrownBy {
          methodThrowingException()
        }

        Then("the method execution is logged")
        expectLogMessageContaining("methodThrowingException")

        And("a span-success is logged with a value of true")
        expectLogMessageContaining("span-success=true")
      }
      "complete the trace with success for methods that throw ignored exceptions" in {
        Given("a method that throws an ignored exception")

        When("the method is invoked")
        an[IllegalArgumentException] should be thrownBy {
          methodWithIgnoredException()
        }

        Then("the method execution is logged")
        expectLogMessageContaining("methodWithIgnoredException")

        And("a span-success is logged with a value of true")
        expectLogMessageContaining("span-success=true")
      }
      "complete the trace with failure for methods that throw exceptions that are not in ignored list" in {
        Given("a method that throws an ignored exception")

        When("the method is invoked")
        a[RuntimeException] should be thrownBy {
          methodWithNonMatchingIgnoredException()
        }

        Then("the method execution is logged")
        expectLogMessageContaining("methodWithNonMatchingIgnoredException")

        And("a span-success is logged with a value of false")
        expectLogMessageContaining("span-success=false")
      }
    }
    "advising methods that have parameters with the TracedData annotation" should {
      "record the value of the parameter in the trace" in {
        Given("a method that has arguments with the TraceData annotation")

        When("the method is invoked")
        methodWithArguments("hello", "bob")

        Then("The method execution is logged")
        expectLogMessageContaining("methodWithArguments")

        And("the values of the arguments that have the TracedData annotation are logged")
        expectLogMessageContaining("hello")

        And(
          "the values of the arguments that have a custom name for the TracedData annotation log using the custom name"
        )
        expectLogMessageContaining("CUSTOM_NAME=bob")
      }
      "record parameters whose value is null" in {
        Given("a method that has arguments with the TraceData annotation")

        When("the method is invoked with a null value")
        methodWithArguments(null, null)

        Then("The method execution is logged")
        expectLogMessageContaining("methodWithArguments")

        And("the parameter values are captured")
        expectLogMessageContaining("foo=")
        expectLogMessageContaining("CUSTOM_NAME=")
      }
      "propagate traced data parameters" in {
        Given("a method that has arguments with the TracedData annotation")
        And("one of those arguments is set to propagate")
        And("the method calls another method that is also traced")

        When("the method is invoked")
        methodWithArgumentsPropagated("boo", "far")

        Then("the main method execution is logged")
        expectLogMessageContainingStrings(Seq("methodWithArgumentsPropagated", "PROPAGATE=boo", "CUSTOM_NAME=far"))

        And("the child span has the propagated parameters")
        expectLogMessageContainingStrings(Seq("methodWithoutArguments", "PROPAGATE=boo"))
      }
    }
    "timing method execution" should {
      "record the execution time of a method that returns normally" in {
        Given("a trace exists")
        Money.Environment.tracer.startSpan("test-timing")
        And("a method that has the Timed annotation")

        When("the method is called")
        methodWithTiming()

        And("the trace is stopped")
        Money.Environment.tracer.stopSpan()

        Then("a message is logged containing the duration of the method execution")
        expectLogMessageContaining("methodWithTiming")
      }
    }
    "testing pointcuts" should {
      "love us some code coverage" in {
        val traceAspect = new TraceAspect()
        traceAspect.traced(null)
        traceAspect.timed(null)
      }
    }
    "advising methods" should {
      "set span name in MDC" in {
        val jp = mock[ProceedingJoinPoint]
        val ann = mock[Traced]
        doReturn("testSpanName").when(ann).value()
        doReturn(None).when(mockMdcSupport).getSpanNameMDC
        testTraceAspect.adviseMethodsWithTracing(jp, ann)

        verify(mockMdcSupport).setSpanNameMDC(Some("testSpanName"))
        verify(mockMdcSupport).setSpanNameMDC(None)
      }
      "save the current span name and reset after the child span is complete" in {
        val jp = mock[ProceedingJoinPoint]
        val ann = mock[Traced]
        doReturn("testSpanName").when(ann).value()
        doReturn(Some("parentSpan")).when(mockMdcSupport).getSpanNameMDC

        testTraceAspect.adviseMethodsWithTracing(jp, ann)

        verify(mockMdcSupport).setSpanNameMDC(Some("parentSpan"))
      }
    }
  }
}
