# [Traccar](https://www.traccar.org)

## Overview

Traccar is an open source GPS tracking system. This repository contains Java-based back-end service. It supports more than 200 GPS protocols and more than 2000 models of GPS tracking devices. Traccar can be used with any major SQL database system. It also provides easy to use [REST API](https://www.traccar.org/traccar-api/).

Other parts of Traccar solution include:

- [Traccar web app](https://github.com/traccar/traccar-web)
- [Traccar Manager app](https://github.com/traccar/traccar-manager)

There is also a set of mobile apps that you can use for tracking mobile devices:

- [Traccar Client app](https://github.com/traccar/traccar-client)

## Features

Some of the available features include:

- Real-time GPS tracking
- Driver behaviour monitoring
- Detailed and summary reports
- Geofencing functionality
- Alarms and notifications
- Account and device management
- Email and SMS support

## Build

See [Project customizations](CUSTOMIZATIONS.md) for the game features in this
fork and [Development instructions](AGENTS.md) for building the server and its
pinned web app. Upstream build instructions are available on the
[Traccar website](https://www.traccar.org/build/).

## Release the Docker image

The image combines this server and the exact web commit recorded in
`traccar-web`. The [desktop Tool](https://github.com/azamuner97/Traccar-tool-for-manhunt-games)
has its own installer and release.

1. Push the web commit to GitHub first. Then commit the updated web reference in
   this server repository and push the server's `project` branch.
2. In GitHub Actions, run **Build Project** on `project` to check the server,
   web app, and MySQL integration.
3. In the server repository, set the GitHub Actions secrets
   `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` using your Docker Hub username
   and an access token with write permission.
4. Run **Build Release** on `project` with a new version tag, for example
   `6.15.3-project.1`. The `preview` value builds test artifacts. A version
   tag publishes the installers to
   GitHub and the images to [Docker Hub](https://hub.docker.com/r/azamuner97/traccar/tags)
   for AMD64 and ARM64. It also updates `latest`. Use the specific version tag
   in [the deployment configuration](docker/compose/.env.production.example).

## Team

- Anton Tananaev ([anton@traccar.org](mailto:anton@traccar.org))
- Andrey Kunitsyn ([andrey@traccar.org](mailto:andrey@traccar.org))

## License

    Apache License, Version 2.0

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
