# Google Gemini AI client for Minecraft.

First, you'll need to set the model you want to use and the API key
in the config file. See the Gemini docs for more info.

You'll need to create some persona files - these are relative to the
server directory and consist of some basic "system instructions." A 
basic description of the NPC will do. Add these to a subdirectory
of the plugins directory, e.g. `plugins/GeminiNPC/personae`

Then add this directory to the config file's `personae-directories` section.
You can add multiple directories, and the plugin will search them all.


```
personae-directories:
    - plugins/GeminiNPC/personae
    - plugins/GeminiNPC/otherpersonae
```

Then you can select an NPC (this is a Citizens2 plugin, so see their
documentation for that) and the trait and a persona with 

```
trait gemininpc
gemini persona dave
```
assuming there's a persona file called `dave` in one of the directories. Here's an example:
```
You are a friendly NPC in a fantasy world. You are a wizard, and have a fondness for the good things
in life.
```

If you talk to the bot after assigning the trait but before setting its persona, you'll get the rather
bland default persona.

Common data to all personae - perhaps describing the setting - can be added by putting a "common" file in the 
main section:
```
main:
    common: plugins/GeminiNPC/common
```
This will be prepended to all the persona strings.


## What is sent

Each turn, the plugin sends JSON block consisting of two elements - context and input. The latter is the actual
string the model should respond to, the former is a JSON-like string that looks like this:
```
context: {
environment: The time is 11:35. The weather is clear.
You are in region The Herald House.
You are at location indoors.
You can see these players: jfinnis
The light level is 11/15 of which 11 is from lamps.
inventory: you are carrying: STICK POPPY 
}
input: {
jfinnis: (enters)
}```
The environment string is the current time and weather, and the playername string is the text the player wrote.


