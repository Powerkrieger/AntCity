# AntCity 🏛️

A city-building game for Android. Collect resources, house your habitants, fortify your city,
build siege equipment in the Hangar and raid other players. Your city lives on your own server,
which verifies that everything you have could legitimately have been built.

## Modules

| Module    | What                                                                         |
|-----------|------------------------------------------------------------------------------|
| `core/`   | Pure Kotlin: game rules, deterministic simulation, battles, API types        |
| `server/` | Ktor + SQLite backend (Docker + Caddy, like GasTrack's)                       |
| `app/`    | Jetpack Compose client                                                       |

The app and server both run **the same `core` code**. That shared code makes the integrity checks possible.

## Game

| Building       | Role                                                               |
|----------------|--------------------------------------------------------------------|
| 🏛️ Town Hall    | Caps the level and number of every other building                  |
| 🏠 House        | Housing; a new habitant moves in every 15 s. Habitants pay taxes 🪙 |
| 🌾 🪵 ⛏️ Farm / Lumber Mill / Quarry | Collectors; only work with enough habitants as workers |
| 📦 Warehouse    | Storage capacity                                                   |
| 🧱 Wall / 🗼 Tower | Fortifications: walls absorb damage, towers shoot back            |
| 🛠️ Hangar       | Builds 🪖 Infantry (L1), 🐏 Rams (L2), ☄️ Catapults (L3)              |

Two builders work in parallel. Raids are resolved on the server: attackers break walls → towers → Town Hall,
and surviving infantry carry home up to 30% of the defender's stock. Raided cities get a 2 h shield,
new cities 24 h. Attacking drops your own shield. All numbers live in `core/.../Rules.kt`.

## How integrity checking works

The client **never uploads its state**. It uploads *actions* (`build`, `upgrade`, `demolish`, `train`)
with a timestamp. The server then:

1. Replays each action against its own authoritative state, advanced to the action's time with
   the shared deterministic simulation (integer-only math, so results match on every device and do
   not depend on how often you advance time). If the city couldn't afford it, had no free builder,
   hadn't unlocked it, etc., the action is rejected.
2. Rejects timestamps in the future (more than 10 s ahead of the server clock), so you can't skip time.
   Actions older than the last server-side change are moved forward to that time, which never helps a
   cheater. Queuing actions while offline works: they sync later.
3. Compares the client's predicted state hash with its own result. An honest client uses identical
   rules, so rejected actions or hash mismatches are logged as **integrity events**.
4. Keeps every city's genesis state and **complete log**. `GET /api/admin/verify` replays every city
   from scratch and confirms it matches what's stored: *could they have built this, given the starting
   state, the time spent and the actions taken?*

## Server deployment (powerserver)

```sh
git clone <this repo> antcity && cd antcity/server
cp .env.example .env            # set ADMIN_TOKEN=$(openssl rand -hex 32)
sed -i 's/<domain>/antcity.example.com/' Caddyfile
docker compose up -d --build
curl https://antcity.example.com/health   # -> ok
```

The Docker build compiles the server from source (build context is the repo root, the Android app is
skipped). Data lives in `server/data/antcity.db`. Back up that file.

Admin endpoints (header `X-Admin-Token`):

- `GET /api/admin/verify`: full replay audit of all cities
- `GET /api/admin/verify/{playerId}`: audit one city
- `GET /api/admin/integrity`: recent suspicious events

### Local development

```sh
./gradlew -PnoApp :server:run      # http://localhost:8080, data in server/data/
```

From the emulator use `http://10.0.2.2:8080` (debug builds allow plain HTTP).

## Tests

```sh
./gradlew -PnoApp :core:test :server:test
```

## API

All `/api` endpoints except `register` need `Authorization: Bearer <token>`.

| Endpoint             | Purpose                                                  |
|----------------------|----------------------------------------------------------|
| `POST /api/register` | `{name}` → player id, secret token, genesis snapshot     |
| `GET /api/state`     | Authoritative snapshot, advanced to now                  |
| `POST /api/sync`     | Upload actions, get per-action results + new snapshot    |
| `GET /api/targets`   | Other cities with their defenses and potential loot      |
| `POST /api/attack`   | `{targetId, army}` → battle report                       |
| `GET /api/reports`   | Your recent battles                                      |
