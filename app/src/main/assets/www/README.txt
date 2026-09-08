Standalone / HTML-to-APK mode
=============================
Put your web project here so that this folder contains index.html directly, e.g.

  app/src/main/assets/www/index.html
  app/src/main/assets/www/css/style.css
  app/src/main/assets/www/js/app.js

When index.html exists here, the app opens it as the home page of every new tab, served from
https://appassets.androidplatform.net/www/ (secure origin, no file:// access).
Then push to GitHub and the "Build APK" workflow produces your APK.
This README.txt is ignored by the app.
