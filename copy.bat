copy target\gemininpc-0.0.2.jar d:\mc\plugins\
rem Copy the example config (personae, quests, templates...) into the plugin's folder. config.yml is left
rem out because the server's copy has its own settings, which this would overwrite.
robocopy exampleconfig d:\mc\plugins\GeminiNPC /E /XF config.yml /NJH /NJS /NDL
