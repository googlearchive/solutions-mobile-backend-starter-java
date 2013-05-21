solutions-mobile-backend-starter-android-client
===============================================

This project is Android native client sample for Mobile Backend Starter.

Disclaimer: This is not an official Google Product.

## Products
- [App Engine][1]
- [Android][2]

## Language
- [Java][3]

## APIs
- [Google Cloud Endpoints][4]

## Setup Instructions
The instruction below lists just some key steps.

For detailed setup instructions and documentation visit [Google App Engine developer site](https://developers.google.com/cloud/samples/mbs).
It may also be helpful to watch this [Google I/O session](https://developers.google.com/events/io/sessions/333508149) and read the corresponding [blog post](http://bradabrams.com/2013/05/google-io-2013-session-overview-from-nothing-to-nirvana-in-minutes-cloud-backend-for-your-android-application-building-geek-serendipity/).

1. Make sure you have Android SDK with Google APIs level 15 or above installed.

2. Import the project into Eclipse by selecting File > Import > General > Existing Projects into Workspace and then supplying the directory where you unzipped the downloaded  project.

3. If you don't have Google API level 16 installed then in project properties,
   select Android and change Project Build Target to Google APIs with API Level 15 or above.

4. Update the value of `PROJECT_ID` in
   `src/com/google/cloud/backend/android/Consts.java` to the app_id of your
   deployed Mobile Backend [5]. Make sure that your Mobile Backend is configured
   with OPEN mode.

5. Run the application. All required jars and other files are included in the GitHub repository.
If you see any compilation or build errors they most likely can be resolved by following the steps in [the documentation](https://developers.google.com/cloud/samples/mbs/getting_started).


[1]: https://developers.google.com/appengine
[2]: http://developer.android.com/index.html
[3]: http://java.com/en/
[4]: https://developers.google.com/appengine/docs/java/endpoints/
[5]: https://github.com/GoogleCloudPlatform/solutions-mobile-backend-starter-java

