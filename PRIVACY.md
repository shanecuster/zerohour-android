# Privacy policy: zerohour for Android

Last updated: 2026-10-22

zerohour is a to-do list and countdown app. It does not collect your data, and
nothing you do in it is sent to the developer. There are no accounts, no ads, no
analytics, no tracking and no crash reporting built into the app.

## What stays on your device

Your tasks, events, notes, completion history and settings are stored in the
app's private storage on your phone. They are not copied to Google's cloud
backups, and uninstalling the app removes them. The app asks for one permission
only: `INTERNET` (a later version that adds reminders will also ask to post
notifications and to schedule exact alarms; this policy will be updated then).

## What the app sends over the network

The app contacts only one place, and only if you turn sync on:

**Your own sync server, only if you turn sync on.** Sync is off by default and
runs only when you tap **sync now**. When used, it copies your tasks, events,
notes and completion history to the CouchDB server whose address, database name,
username and password you entered. The developer has no access to it. The
address must use `https://`; plain `http://` is not allowed. Your password is
stored encrypted with a key held in the Android Keystore, never in plain text,
and it is sent only to the server you chose. Sync is disabled for the sample
(demo) data that debug builds show, so nothing fictional can reach your server.

## Reminders and notifications

Reminders (a later version) are scheduled and shown entirely on your device by
Android's own alarm and notification system. No reminder, and nothing about when
your tasks or events are due, is sent anywhere.

## Files you export

If a future version writes an export file, it goes only to the location you pick
in Android's file picker. If you pick a cloud folder, that service's policy
applies to the file.

## Your control

You can edit or delete any task or event at any time, turn sync off, and
uninstall the app. Data already copied to your own sync server is removed from
that server on the next sync after you delete it here.

## Google Play

If you installed from Google Play, Google may collect crash and performance data
(Android vitals) under its own policy, depending on your device settings. That
collection is done by Google, not by zerohour.

## Changes and questions

If this policy changes, the new version is published in this file, with its
history in the repository. Questions can be raised as an issue at
https://github.com/shanecuster/zerohour-android/issues.
