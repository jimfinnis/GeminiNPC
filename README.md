# Google Gemini AI client trait for Citizens2 in Minecraft

Have conversations with NPCs using the Gemini AI model. This plugin
uses the Gemini API to provide a conversational interface for NPCs
in Minecraft. It is designed to be used with the Citizens2 plugin.

Each NPC can have a different persona, and the plugin will send
context information to the model to help it generate appropriate
responses. The plugin is designed to be used with the JCFUtils
plugin, which provides a way to get information about regions in the world.

It can also interface with the Sentinel trait, so you can tell the NPC 
to guard particular players and follow them.

## Setup

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

### Common data

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
{
  "context": {
    "time": "19:31",
    "weather": "clear",
    "region": {
      "name": "The Herald House"
    },
    "location": "indoors",
    "location description": "A quiet living room",
    "nearbyPlayers": [
      "jfinnis"
    ],
    "light": "11/15",
    "skylight": "10/15",
    "lamplight": "11/15",
    "inventory": [
      "STICK",
      "POPPY"
    ]
  },
  "input": "jfinnis: (enters)"       # NOTE - this is a message sent automatically when a player arrives nearby
}
```
It's worth noting that only parts of the context that have changed since the last turn are sent. So quite often
the context will be empty or just contain the time.

Most of what the context contains is obvious, but here are some notes:

* `location` is a nearby waypoint name - GeminiNPC has its own waypoint system. NPCs are informed of their waypoints in the initial system instruction.
* `location description` is a description of the waypoint.
* `region` is information from the JCFUtils plugin's region mechanism - it gives the region name and description (if there is one).
