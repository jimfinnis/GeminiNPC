# Google Gemini AI client for Minecraft.

First, you'll need to set the model you want to use and the API key
in the config file. See the Gemini docs for more info.

You'll need to create some persona files - these are relative to the
server directory and consist of some basic "system instructions." A 
basic description of the NPC will do.

Then you'll need to add these to the config file in a "personae"
section like this:

```
personae:
    dave: plugins/GeminiNPC/davepersona
    barry: plugins/GeminiNPC/barry
```

Then you can select an NPC (this is a Citizens2 plugin, so see their
documentation for that) and add a persona with 

```
gemini persona dave
```
or similar.

Don't talk to the bot before you do this. It won't break, but the
blank default persona will really creep you out.

Common data to all personae - perhaps describing the setting - can be added by putting a "common" file in the 
main section:
```
main:
    common: plugins/GeminiNPC/common
```
This will be prepended to all the persona strings.


## What is sent

Each turn, the plugin sends a string that looks like this:
```
environment: It is evening. It is raining.
playername: Hello, NPC - how are you this evening?
```
The environment string is the current time and weather, and the playername string is the text the player wrote.


