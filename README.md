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

### YAML persona files
Persona files can be plain text as above, but they can also be YAML files. The structure of such a file
is:
```
string: |
    This is my persona string. It can be multiple lines.

# "You are {{gender}}" is prepended to the entire persona string. This sets the {{gender}} for
# an NPC if none is specified with the "/gemini gender" command. It is optional - if omitted,
# a value given in the main config.yml is used. If that's not present "non-binary" is used.

default-gender: 

# This is a list of template values that can be used in the persona string.

template-values:
    key: value
    key2: value2
```


### Common data

Common data to all personae - perhaps describing the setting - can be added by putting a "common" file in the 
main section:
```
main:
    common: plugins/GeminiNPC/common
```
This will be prepended to all the persona strings. Note that the template values `{{name}}` and `{{gender}}` are useful
here, and there may be others that are handy. 

## Templating

Personae are passed through the [basis-template](https://github.com/badlogic/basis-template) templating engine. See that
page for more details. The transformation takes place only once, however - when the AI is to be called for the first time.
The following template values are available:
* `name` - the name of the NPC
* `gender` - the gender of the NPC (settable with `/gemini gender` and defaulted from the `default-gender` setting in the main section
of the config file)
* `isSentinel` is true if the NPC has the Sentinel trait

More templates values can be set in two ways:
* add a string to the `template-values` section of the config file
* add a file to a directory listed in the `template-value-directories` key of the main section of the config.

The following template functions are available:
* `choose(list)` - randomly choose one of the items in the list. Sublists are permitted - see below.
* `pick(list, n, delimiter)` - randomly choose `n` items from the list and join them with the delimiter. The same item
cannot be chosen multiple times. Sublists are permitted - see below.
* `random(lower, upper)` - randomly choose an integer between lower (inclusive) and upper (exclusive)

Remember that this code only runs once, so if you want to change the template values, you'll need to reload the NPCs
with `/gemini reload` or restart the server.

### Sublists
If an item chosen by `pick` or `choose` it itself a list, then an item will be chosen randomly from the nested list
while (in the case of `pick`) the list itself will be removed from the list of items to choose from. This means that
you can create mutually exclusive items by having a list of lists. See the next section for an example.

## A templated YAML persona example

Here's an example of a YAML persona file that uses the templating engine. It uses the `pick` function to randomly
choose an item from a list of personality traits. Note the two sublists in the `random_personality_features` list,
used for mutually exclusive traits. The `pick` function will choose one of the sublists and then choose one of the items
within that sublist.
```
string: |
        You are a soldier, patrolling for monsters.
        {{pick(random_personality_features,2,"\n")}}

template-values:
    random_personality_features:
        - You really like tea.
        - You tend to ramble in your speech.
        -
            - You dislike daylight and prefer the dark.
            - You are scared of the dark.
        -
            - You are scared of monsters.
            - You quite like monsters and wish you could talk to them rather than kill.
        - You are plain-spoken.
        - You like working in the forge.
        - You don't like being outside, because of the dirt.
        - You have a tendency to shout.
        - You tend to ramble in your speech.
        - You are rather fed up with this place.
        - You are an agent of Chaos.
        - You love the bees.
        - You are a little too fond of bad puns.
        - You are a little too fond of swearing.
```



## What is sent

A lot of the details here may change!

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

