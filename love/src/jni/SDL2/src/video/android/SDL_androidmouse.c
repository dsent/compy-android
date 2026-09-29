/*
  Simple DirectMedia Layer
  Copyright (C) 1997-2023 Sam Lantinga <slouken@libsdl.org>

  This software is provided 'as-is', without any express or implied
  warranty.  In no event will the authors be held liable for any damages
  arising from the use of this software.

  Permission is granted to anyone to use this software for any purpose,
  including commercial applications, and to alter it and redistribute it
  freely, subject to the following restrictions:

  1. The origin of this software must not be misrepresented; you must not
     claim that you wrote the original software. If you use this software
     in a product, an acknowledgment in the product documentation would be
     appreciated but is not required.
  2. Altered source versions must be plainly marked as such, and must not be
     misrepresented as being the original software.
  3. This notice may not be removed or altered from any source distribution.
*/

#include "../../SDL_internal.h"

#if SDL_VIDEO_DRIVER_ANDROID

#include "SDL_androidmouse.h"

#include "SDL_events.h"
#include "../../events/SDL_mouse_c.h"

#include "../../core/android/SDL_android.h"

/* See Android's MotionEvent class for constants */
#define ACTION_DOWN       0
#define ACTION_UP         1
#define ACTION_MOVE       2
#define ACTION_HOVER_MOVE 7
#define ACTION_SCROLL     8
#define BUTTON_PRIMARY    1
#define BUTTON_SECONDARY  2
#define BUTTON_TERTIARY   4
#define BUTTON_BACK       8
#define BUTTON_FORWARD    16

typedef struct
{
    int custom_cursor;
    int system_cursor;

} SDL_AndroidCursorData;

/* Last known Android mouse button state (includes all buttons) */
static int last_state;

/* Blank cursor */
static SDL_Cursor *empty_cursor;

static SDL_Cursor *Android_WrapCursor(int custom_cursor, int system_cursor)
{
    SDL_Cursor *cursor;

    cursor = SDL_calloc(1, sizeof(*cursor));
    if (cursor) {
        SDL_AndroidCursorData *data = (SDL_AndroidCursorData *)SDL_calloc(1, sizeof(*data));
        if (data) {
            data->custom_cursor = custom_cursor;
            data->system_cursor = system_cursor;
            cursor->driverdata = data;
        } else {
            SDL_free(cursor);
            cursor = NULL;
            SDL_OutOfMemory();
        }
    } else {
        SDL_OutOfMemory();
    }

    return cursor;
}

static SDL_Cursor *Android_CreateDefaultCursor()
{
    return Android_WrapCursor(0, SDL_SYSTEM_CURSOR_ARROW);
}

static SDL_Cursor *Android_CreateCursor(SDL_Surface *surface, int hot_x, int hot_y)
{
    int custom_cursor;
    SDL_Surface *converted;

    converted = SDL_ConvertSurfaceFormat(surface, SDL_PIXELFORMAT_ARGB8888, 0);
    if (converted == NULL) {
        return NULL;
    }
    custom_cursor = Android_JNI_CreateCustomCursor(converted, hot_x, hot_y);
    SDL_FreeSurface(converted);
    if (!custom_cursor) {
        SDL_Unsupported();
        return NULL;
    }
    return Android_WrapCursor(custom_cursor, 0);
}

static SDL_Cursor *Android_CreateSystemCursor(SDL_SystemCursor id)
{
    return Android_WrapCursor(0, id);
}

static void Android_FreeCursor(SDL_Cursor *cursor)
{
    SDL_AndroidCursorData *data = (SDL_AndroidCursorData *)cursor->driverdata;
    if (data->custom_cursor != 0) {
        Android_JNI_DestroyCustomCursor(data->custom_cursor);
    }
    SDL_free(cursor->driverdata);
    SDL_free(cursor);
}

static SDL_Cursor *Android_CreateEmptyCursor()
{
    if (empty_cursor == NULL) {
        SDL_Surface *empty_surface = SDL_CreateRGBSurfaceWithFormat(0, 1, 1, 32, SDL_PIXELFORMAT_ARGB8888);
        if (empty_surface) {
            SDL_memset(empty_surface->pixels, 0, (size_t)empty_surface->h * empty_surface->pitch);
            empty_cursor = Android_CreateCursor(empty_surface, 0, 0);
            SDL_FreeSurface(empty_surface);
        }
    }
    return empty_cursor;
}

static void Android_DestroyEmptyCursor()
{
    if (empty_cursor) {
        Android_FreeCursor(empty_cursor);
        empty_cursor = NULL;
    }
}

static int Android_ShowCursor(SDL_Cursor *cursor)
{
    if (cursor == NULL) {
        cursor = Android_CreateEmptyCursor();
    }
    if (cursor) {
        SDL_AndroidCursorData *data = (SDL_AndroidCursorData *)cursor->driverdata;
        if (data->custom_cursor) {
            if (!Android_JNI_SetCustomCursor(data->custom_cursor)) {
                return SDL_Unsupported();
            }
        } else {
            if (!Android_JNI_SetSystemCursor(data->system_cursor)) {
                return SDL_Unsupported();
            }
        }
        return 0;
    } else {
        /* SDL error set inside Android_CreateEmptyCursor() */
        return -1;
    }
}

static int Android_SetRelativeMouseMode(SDL_bool enabled)
{
    if (!Android_JNI_SupportsRelativeMouse()) {
        return SDL_Unsupported();
    }

    if (!Android_JNI_SetRelativeMouseEnabled(enabled)) {
        return SDL_Unsupported();
    }

    return 0;
}

void Android_InitMouse(void)
{
    SDL_Mouse *mouse = SDL_GetMouse();

    mouse->CreateCursor = Android_CreateCursor;
    mouse->CreateSystemCursor = Android_CreateSystemCursor;
    mouse->ShowCursor = Android_ShowCursor;
    mouse->FreeCursor = Android_FreeCursor;
    mouse->SetRelativeMouseMode = Android_SetRelativeMouseMode;

    SDL_SetDefaultCursor(Android_CreateDefaultCursor());

    last_state = 0;
}

void Android_QuitMouse(void)
{
    Android_DestroyEmptyCursor();
}

/* love2d-mod-start: a mouse's back button is its right button
 * Translate Android mouse button state to the SDL buttons it holds.
 * Some Android builds report a mouse's right button as BUTTON_BACK
 * rather than BUTTON_SECONDARY; on such a build the press comes only
 * in hover events, from which SDLMouseButtonKeys.java sends it here,
 * and the Back key that comes with it is dropped. BUTTON_BACK is the
 * right button, held while either Android button is. */
static Uint32 ButtonMask(int state)
{
    Uint32 mask = 0;
    if (state & BUTTON_PRIMARY) {
        mask |= SDL_BUTTON_LMASK;
    }
    if (state & (BUTTON_SECONDARY | BUTTON_BACK)) {
        mask |= SDL_BUTTON_RMASK;
    }
    if (state & BUTTON_TERTIARY) {
        mask |= SDL_BUTTON_MMASK;
    }
    if (state & BUTTON_FORWARD) {
        mask |= SDL_BUTTON_X1MASK;
    }
    return mask;
}

/* Sends SDL every button whose state changed, releases first, since
 * one Android event may change several buttons at once. */
static void SendButtonChanges(SDL_Window *window, int state)
{
    Uint32 before = ButtonMask(last_state);
    Uint32 after = ButtonMask(state);
    Uint8 button;

    last_state = state;
    for (button = SDL_BUTTON_LEFT; button <= SDL_BUTTON_X1; ++button) {
        if ((before & ~after) & SDL_BUTTON(button)) {
            SDL_SendMouseButton(window, 0, SDL_RELEASED, button);
        }
    }
    for (button = SDL_BUTTON_LEFT; button <= SDL_BUTTON_X1; ++button) {
        if ((after & ~before) & SDL_BUTTON(button)) {
            SDL_SendMouseButton(window, 0, SDL_PRESSED, button);
        }
    }
}
/* love2d-mod-end: a mouse's back button is its right button */

void Android_OnMouse(SDL_Window *window, int state, int action, float x, float y, SDL_bool relative)
{
    /* love2d-mod: the changes/button locals went with TranslateButton */
    if (window == NULL) {
        return;
    }

    switch (action) {
    /* love2d-mod-start: press and release both send every changed button */
    case ACTION_DOWN:
    case ACTION_UP:
        SDL_SendMouseMotion(window, 0, relative, (int)x, (int)y);
        SendButtonChanges(window, state);
        break;
    /* love2d-mod-end: press and release both send every changed button */

    case ACTION_MOVE:
    case ACTION_HOVER_MOVE:
        SDL_SendMouseMotion(window, 0, relative, (int)x, (int)y);
        break;

    case ACTION_SCROLL:
        SDL_SendMouseWheel(window, 0, x, y, SDL_MOUSEWHEEL_NORMAL);
        break;

    default:
        break;
    }
}

#endif /* SDL_VIDEO_DRIVER_ANDROID */

/* vi: set ts=4 sw=4 expandtab: */
