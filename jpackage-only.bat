rmdir /s /q jpackage-input 2>nul & mkdir jpackage-input
copy /y target\flola.jar jpackage-input\
jpackage --type app-image --name FLOLA --input jpackage-input --main-jar flola.jar --main-class com.hemisus.flola.Launcher --app-version 0.1.0 --dest dist