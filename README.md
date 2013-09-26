# Mobile Backend Starter Java

This application implements Mobile Backend Starter using Google Cloud Endpoints, App Engine and Java.

## Copyright
Copyright 2013 Google Inc. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

[http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0)
lo
Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

## Disclaimer
This is not an official Google Product.

## Products
- [App Engine][1]

## Language
- [Java][3]

## APIs
- [Google Cloud Endpoints][2]

## Setup Instructions
You typically need to use this repository only if you want to extend the functionality provided by Mobile Backend Starter (MBS). Otherwise, you can deploy it directly from [Google Cloud Platform site](https://cloud.google.com/solutions/mobile) by clicking Try It Now.

### Requirements
Confirm that your development envoriment fulfills the Requirements of [MBS Getting Started doc](https://developers.google.com/cloud/samples/mbs/getting_started).

### Steps

1. Download MBS backend source code.  Click the ZIP button on the [MBS backend source code page](https://github.com/GoogleCloudPlatform/solutions-mobile-backend-starter-java) on GitHub to download the source code package of MBS backend.

2. MBS requires the following jar files which are not included in the source code package above:
   * Download gcm-server.jar file, open Android SDK Manager and choose Extras > Google Cloud Messaging for Android Library. This creates a gcm directory under YOUR_ANDROID_SDK_ROOT/extras/google/ containing "gcm-server/dist" subdirectory which has gcm-server.jar file.
   * Download [google-gson-2.1-release.zip](https://google-gson.googlecode.com/files/google-gson-2.1-release.zip) and extract the zip file, and you have "google-gson-2.1" directory which has gson-2.1.jar file.
   * Download [json-simple.1.1.1.jar](https://json-simple.googlecode.com/files/json-simple-1.1.1.jar) file.
   * Download [JavaPNS-2.2.jar](https://code.google.com/p/javapns/downloads/list) file.
   * Download [common-codec-1.8.jar](http://commons.apache.org/proper/commons-codec/download_code) file.
   * Download [bcprov-jdk15on-146.jar](http://www.bouncycastle.org/download/bcprov-jdk15on-146.jar) file.  By default, the downloaded jar file is signed.  Execute the following command to unsign this jar file:

         zip -d bcprov-jdk15on-146.jar META-INF/MANIFEST.MF

   * Download [log4j-1.2.17.jar](http://logging.apache.org/log4j/1.2/download.html) file.

8. Create a Web Application Project on Eclipse, select File > New > Web Application Project to show New Web Application Project dialog. Enter the followings:

   - Enter Project Name: MobileBackend
   - Enter Package Name: com.google.cloud.backend
   - Deselect "Use Google Web Toolkit" checkbox
   - Deselect "Generate project sample code" checkbox
   - Click Finish

9. Copy the src and war directories to the project. Extract the source code package downloaded at the step 1. Select "src" and "war" directories, copy them, and paste them to the root directory of CloudBackend project on Eclipse.

10. Add the jar files into lib directory.  Copy gcm-server.jar, gson-2.1.jar, json-simple.1.1.1.jar, javaPNS-2.2.jar,common-codec-1.8, and bcprov-jdk5-14.jar (from step 2 to 7) to war/WEB-INF/lib directory. Select the six files and select Build Path > Add to Build Path on right-click menu. This will remove all the errors you have on the Problems tab.

11. Deploy the backend.  Edit war/WEB-INF/appengine-web.xml and insert your app id in the "application" element below:

  `<application>!!! ENTER YOUR APP ID HERE !!!</application>`

12. Finally, right-click CloudBackend project and select Google > Deploy to App Engine. This will deploy the backend to your app id. Go through the [Getting Starter steps](https://developers.google.com/cloud/samples/mbs/getting_started) to confirm the backend functions are working properly.

[1]: https://developers.google.com/appengine
[2]: https://developers.google.com/appengine/docs/java/endpoints/
[3]: http://java.com/en/

