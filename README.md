# JJRPC
A work-in-progress Java port of the C# XboxChef JRPC Library
------------------------------------------------------------------------------------------
- This has been tested with my own (as of yet unreleased) updated Java RTM tool.
- Not every ported feature is tested as I do not have time nor really the deep 360 knowledge needed to test it fully.
- This release is just a posting of my current progress and should be in no way considered a replacement or upgrade to the real C# JRPC.
- As I find time to test more, I will update this repo accordingly.
- There are some minor xdevkit ports included as well, to assist the JRPC methods.
------------------------------------------------------------------------------------------
- Anyone may use or fork this project for their own purposes, just credit me if you use it.
- If you make something cool with it, I would love to see it.
- Non-functional or missing features may be present. Java doesn't natively handle memory so I had to write methods to handle it, it's some pretty complex math so I might have screwed it up. Sorry if so.
------------------------------------------------------------------------------------------
Beyond that, to edit it, just download the repo into IntelliJ IDEA (Free edition works), make your changes, then run:
 `.\gradlew shadowjar` 
in the terminal. This will build the JAR library to:
 `\build\libs\JJRPC.jar`
------------------------------------------------------------------------------------------
Requirements:
- JetBrains IntelliJ IDEA Community Editon
- JDK 17
- Gradle 8.3  
