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

## What it does *not* establish (yet)

The DNS check itself is straightforward. The harder part — actually proving
the background-vs-foreground hypothesis — needs one of two things this repo
doesn't currently do:

- **Real extended-usage evidence**: correlating each check's timestamp
  against whether the app was foregrounded or backgrounded at that moment
  (via `ProcessLifecycleOwner`), run over hours of real usage, so failures
  can be checked for clustering during background windows.
- **A forced reproduction**: an instrumented test that forces the device
  into Doze (`adb shell dumpsys deviceidle force-idle`) and checks DNS
  immediately after, as a fast, repeatable — if narrower — signal. This only
  covers the Doze mechanism specifically, not App Standby buckets or
  OEM-specific battery management layered on top of stock Android.

Neither is implemented here. This repo is the isolated repro of the check
itself, not the finished investigation.

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

## Running it

Open in Android Studio, sync Gradle, run on an emulator or device (minSdk
24). The log file path is shown on screen; failures (if any) and the final
summary land there.
