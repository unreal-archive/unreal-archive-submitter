# Unreal Archive Content Submitter

A simple service and web page for accepting content submissions.

- allows user to submit file via HTTP form upload
- scan and index file with `unreal-archive`
- if a new file, clone/update `unreal-archive-data` repository and add content
  data
- push `unreal-archive-data` to a new branch and create a pull request via 
  Github API
- someone will review the PR and accept it

How

- submission process needs to be asynchronous, as the scan/index/clone/push
  steps may take some time
- use long polling on client, build up log of events on server and update 
  client as progress happens

See: https://github.com/eclipse/egit-github/tree/master/org.eclipse.egit.github.core

