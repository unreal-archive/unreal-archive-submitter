# Unreal Archive Content Submitter

A simple service and web page for accepting content submissions.

- allows user to submit file via HTTP form upload
- virus scan file using `clamscan`
- scan and index file with `unreal-archive`
- if a new file, clone/update `unreal-archive-data` repository and add content
  data
- push `unreal-archive-data` to a new branch and create a pull request via 
  Github API
- someone will review the PR and accept it

### How

- submission process needs to be asynchronous, as the scan/index/clone/push
  steps may take some time
- use long polling on client, build up log of events on server and update 
  client as progress happens

### Config

Service is configured using environment variables:

- `GH_REPO`: URL of the archive data repository to clone and push to 
- `GH_USERNAME`: username of user used to clone, push, and create pull requests
- `GH_PASSWORD`: token for user
- `GH_EMAIL`: email address to use on commits
- `BIND_HOST`: bind web service to this host
- `BIND_PORT`: web service listens on this port
- `ALLOWED_ORIGIN`: comma separated list of hosts from which to accept CORS
   requests for job queries and file uploads
- `JOBS_PATH`: path to where job history is stored
- `STATS_HOST`: host name/address of StatsD service
- `STATS_PORT`: port on `STATS_HOST` of StatsD service
- `UPLOAD_PATH`: path to upload temporary files to
- `CLAM_SOCKET`: path to a ClamD "LocalSocket" file, which can be reused. if 
   this is not set, a new clamd process will be created with its own socket.

**Data stores (for content hosting):**

- No-Op store:
  - `STORE=NOP`: no-op storage, used for offline testing

- WebDAV store:
  - `STORE=DAV`: use WebDav storage, typically only used for testing
  - `DAV_IMAGES`, `DAV_CONTENT`: set the URL to PUT image or content files 
     respectively
  - `DAV_URL`: default DAV URL to PUT files, only used if either of the 
     above are not set

- Backblaze B2 store:
  - `STORE=B2`: use B2 storage, typically for production
  - `B2_ACC`: B2 account ID
  - `B2_KEY`: B2 app key
  - `B2_BUCKET_IMAGES`, `B2_BUCKET_CONTENT`: B2 bucket IDs for image and
    content files respectively
  - `B2_BUCKET`: default B2 bucket ID to store files, only used if either of 
    the above are not set
