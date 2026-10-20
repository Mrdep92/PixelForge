# PixelForge — Final Design Document

## Vision
PixelForge is a living 2D retro pixel office on Android where a team of AI agents work like real employees.  
They only execute tasks after the user approves their plan (Approval Gate).  
Project context is kept strictly consistent via a Project Bible.

## Agent Roles
- Architect
- Coder
- Auditor
- Debugger
- Creative
- Business
- General

## Approval Gate Flow
1. User gives command (text or voice)
2. Architect creates plan
3. Relevant agents submit short reports
4. User Approves / Revises / Rejects
5. Agents execute in parallel
6. Progress visible in the office (movement + bubble chat)

## Project Bible
- Stored as Markdown inside each Project
- Always injected into every agent prompt
- Editable by user
- Protected during context compression

## Model Routing
- Primary: OpenRouter
- Optional: OmniRoute
- Auto-switch on rate limit or context full
- Role-based model preference

## Office Life (Level 1–3)
- Level 1: Move, sit, stand
- Level 2: Idle animations (type, coffee, stretch…)
- Level 3: Small events + bubble chat

## Privacy
- API keys stored in Android Keystore
- All project & chat data stored locally
- No third-party server owned by us stores user data

## Tech
Kotlin + Jetpack Compose + Room + WorkManager
