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

- `GIT_REPO`: URL of the archive data repository to clone and push to 
- `GIT_USERNAME`: username of user used to clone and push repository changes
- `GIT_PASSWORD`: token for user
- `GIT_EMAIL`: email address to use on commits
- `GH_TOKEN`: github personal access token, for opening pull requests
- `BIND_HOST`: bind web service to this host
- `BIND_PORT`: web service listens on this port
- `ALLOWED_ORIGIN`: comma separated list of hosts from which to accept CORS
   requests for job queries and file uploads
- `JOBS_PATH`: path to where job history is stored
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

- S3 compatible object store:
  - `STORE=S3`: use S3 storage
  - `S3_KEY`: S3 key ID
  - `S3_SECRET`: S3 access secret
  - `S3_BUCKET_IMAGES`, `BS3_BUCKET_CONTENT`: bucket names for image and
    content files respectively
  - `S3_BUCKET`: default bucket name to store files, only used if either of 
    the above are not set
  - `S3_ENDPOINT`: provide the root of your storage API
    - `S3_ENDPOINT_IMAGES`, `BS3_ENDPOINT_CONTENT`: optional alternative 
       root endpoints if using different ones per content type
    - example: `https://s3.amazonaws.com/`
  - `S3_URL`: provide the public URL of your storage bucket in the appropriate
     region
    - example: `https://__BUCKET__.s3.eu-west-2.amazonaws.com/__NAME__`
    - `__BUCKET__` and `__NAME__`  will be replaced by the bucket and uploaded
      filenames respectively
