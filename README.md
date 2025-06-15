# Poker-online

A modular, server-backed Texas Hold'em poker engine written in Kotlin, designed for extensibility and online multiplayer play.\
(Server development in-progress)

## Features

- Modular Architecture: Core poker logic is separated from server and persistence layers, making it easy to extend or adapt for other poker variants;
- Ktor-based Server (WIP): RESTful API for poker table management and player actions;
- Serverless Lambda (WIP): RESTful API for poker table management and player actions;
- Persistence Layer: Pluggable repository pattern, with support for AWS DynamoDB (WIP) (local or remote) for storing table state;
- Serialization: Uses Kotlinx Serialization for data transfer and persistence;
- Tested: Core logic is covered by unit and integration tests.
