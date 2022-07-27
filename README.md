# A video chat app using the Daily Client SDK for Android

This demo is meant to showcase a basic video chat app that uses Daily's native [Android SDK](https://docs.daily.co/guides/products/mobile#introducing-dailys-native-mobile-libraries-beta) mobile library.

## Prerequisites

- [Sign up for a Daily account](https://dashboard.daily.co/signup).
- [Create a Daily room URL](https://help.daily.co/en/articles/4202139-creating-and-viewing-rooms) to test a video call quickly, and then enter that URL into the demo (_this is NOT recommended for production apps!_).
- Install [Android Studio](https://developer.android.com/studio) and its pre-requisites. The [install](https://developer.android.com/studio/install) and [run](https://developer.android.com/studio/run) instructions ought to cover what you need.

## How the demo works

In the demo app, a user must enter a URL for a [Daily Room](https://docs.daily.co/reference#rooms), then press Join. The app will find the meeting room and join the call. The app initializes a Call Client, which keeps track of important information about the meeting, like other participants (including their audio and video tracks) and the things they do on the call (e.g. muting their mic or leaving), and provides methods for interacting with the meeting. The app leverages this object to update its state accordingly, and to carry out user actions like muting or changing track-publishing statuses. When the user leaves the meeting room, the Call Client remains, but their call has ended. The Call Client is destroyed when the application exits.

When testing or running this demo, you'll likely use a room you've manually created for calls. A production application will likely need to use the [Daily REST API](https://docs.daily.co/reference/rest-api) to create rooms on the fly for your users, which necessitates the use of a sensitive Daily API key. You likely don't want to embed this key in a production app. We recommend running a web server and keeping sensitive things like API keys there instead.

Please note this project is designed to work with rooms that have [privacy](https://www.daily.co/blog/intro-to-room-access-control/) set to `public`. If you are hardcoding a room URL, please bear in mind that token creation, pre-authorization and knock-for-access have not been implemented here or in the Daily Client SDK for Android, meaning you may not be able to join non-public meeting rooms using this demo for now.

## Running locally

1. Clone this repository locally, i.e.: `git clone git@github.com:daily-demos/daily-android-demo.git`
2. Open the DailyDemo directory in Android Studio.
3. Install dependencies by syncing your Android Studio project's gradle files using the Android Studio UI popups.
4. Make the project.
5. Run the project, either on a simulator (which will not have webcam access, but which can send an image in place of a real camera stream), or on a connected Android device.
6. Connect to the room URL you are testing, and to see it work, connect again either in another simulator, on another device, or directly using a web browser. Careful of mic feedback! You might want to mute one or both side's audio if they're near each other.

## Contributing and feedback

Let us know how experimenting with this demo goes! Feel free to [open an Issue](https://github.com/daily-demos/daily-android-demo/issues), or reach us any time at `help@daily.co`.

## What's next

To get to know more Daily API methods and events, explore our other demos, like [how to add your own chat interface](https://github.com/daily-co/daily-demos/tree/main/static-demos/simple-chat-demo).
