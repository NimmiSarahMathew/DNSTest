# DNSTest

A small Android app that continuously checks DNS resolution and logs the
results, built to investigate whether network calls made while an app is in
the background are more likely to fail than calls made in the foreground.
Android does restrict background network access under certain conditions,
though apps can often be granted exemptions from these restrictions.

## Context

Built while debugging a production issue: an app was seeing intermittent
network failures, and the working theory was that some of them were DNS
resolution failures specific to the app being backgrounded rather than
generic connectivity loss. This is a minimal, standalone repro of the DNS
check itself — pointed at a public domain here rather than the original
target — put together to observe the failure pattern in isolation, without
the rest of the original app's complexity in the way.

## What it does

- Resolves a domain (`DnsMonitor.Constants.DOMAIN`, currently `google.com`)
  on a fixed interval and records success or failure.
- On failure, classifies the cause where possible (`UnknownHostException`,
  `SocketTimeoutException`, generic `IOException`) rather than just logging
  "it failed."
- Writes only failures to a log file, to keep the file small over a long
  run, plus an hourly running summary and a final report (success rate,
  failure rate, average interval) when stopped.
- Shows the same data live in a simple Compose screen while it runs.

## Structure

```
DnsMonitor.kt          ViewModel: runs the periodic check, tracks state as
                       StateFlow, writes the log. Takes the log File directly
                       in its constructor -- no Context/Application dependency,
                       so it's constructible in a plain JVM test.
DNSMonitorScreen.kt    the Compose UI showing live counts and the last result
MainActivity.kt        wires the two together; builds the log file and
                       supplies it to DnsMonitor via a ViewModelProvider.Factory
```
