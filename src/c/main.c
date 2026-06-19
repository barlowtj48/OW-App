#include <pebble.h>

// Message keys are generated from package.json -> MESSAGE_KEY_*
//   Connected       : uint8  (0 = no board, 1 = connected)
//   BatteryPercent  : uint8  (0..100)
//   Speed           : uint16 (mph * 10, e.g. 124 == 12.4 mph)

// Set to 1 to animate fake values with no companion attached (emulator gate).
// Set to 0 for real use so only AppMessage drives the display.
#define DEBUG_STUB 1

static Window *s_window;
static TextLayer *s_time_layer;
static TextLayer *s_status_layer;
static TextLayer *s_battery_label_layer;
static TextLayer *s_battery_layer;
static TextLayer *s_speed_label_layer;
static TextLayer *s_speed_layer;

static char s_time_buffer[8];
static char s_battery_buffer[8];
static char s_speed_buffer[12];

// Current board data.
static bool s_connected = false;
static int s_battery = 0;      // percent
static int s_speed_tenths = 0; // mph * 10

static void update_time(void)
{
  // Respects the user's 12h/24h setting.
  clock_copy_time_string(s_time_buffer, sizeof(s_time_buffer));
  text_layer_set_text(s_time_layer, s_time_buffer);
}

static void update_stats_display(void)
{
  if (s_connected)
  {
    snprintf(s_battery_buffer, sizeof(s_battery_buffer), "%d%%", s_battery);
    snprintf(s_speed_buffer, sizeof(s_speed_buffer), "%d.%d mph",
             s_speed_tenths / 10, s_speed_tenths % 10);
    text_layer_set_text(s_status_layer, "OneWheel");
  }
  else
  {
    snprintf(s_battery_buffer, sizeof(s_battery_buffer), "--%%");
    snprintf(s_speed_buffer, sizeof(s_speed_buffer), "-- mph");
    text_layer_set_text(s_status_layer, "Disconnected");
  }
  text_layer_set_text(s_battery_layer, s_battery_buffer);
  text_layer_set_text(s_speed_layer, s_speed_buffer);
}

static void tick_handler(struct tm *tick_time, TimeUnits units_changed)
{
  update_time();
}

// ----- AppMessage (data from the Android companion via PebbleKit) -----

static void inbox_received_callback(DictionaryIterator *iter, void *context)
{
  Tuple *connected_t = dict_find(iter, MESSAGE_KEY_Connected);
  Tuple *battery_t = dict_find(iter, MESSAGE_KEY_BatteryPercent);
  Tuple *speed_t = dict_find(iter, MESSAGE_KEY_Speed);

  if (connected_t)
  {
    s_connected = connected_t->value->uint8 != 0;
  }
  if (battery_t)
  {
    s_battery = battery_t->value->uint8;
  }
  if (speed_t)
  {
    s_speed_tenths = speed_t->value->uint16;
  }
  update_stats_display();
}

static void inbox_dropped_callback(AppMessageResult reason, void *context)
{
  APP_LOG(APP_LOG_LEVEL_ERROR, "AppMessage dropped: %d", (int)reason);
}

// ----- Debug stub: drives fake data so the UI can be validated alone -----

#if DEBUG_STUB
static void stub_tick(void *data)
{
  static int t = 0;
  t++;

  s_connected = true;
  // Battery drains 100 -> 0 then repeats.
  s_battery = 100 - (t % 101);
  // Speed sweeps 0.0 -> 20.0 mph and back.
  int phase = t % 40;
  int mph_tenths = (phase < 20 ? phase : 40 - phase) * 10; // 0..200
  s_speed_tenths = mph_tenths;

  update_stats_display();
  app_timer_register(1000, stub_tick, NULL);
}
#endif

// ----- Window / layout -----

static TextLayer *make_label(Layer *root, GRect frame, const char *font_key,
                             const char *text)
{
  TextLayer *layer = text_layer_create(frame);
  text_layer_set_background_color(layer, GColorClear);
  text_layer_set_text_color(layer, GColorWhite);
  text_layer_set_text_alignment(layer, GTextAlignmentCenter);
  text_layer_set_font(layer, fonts_get_system_font(font_key));
  if (text)
  {
    text_layer_set_text(layer, text);
  }
  layer_add_child(root, text_layer_get_layer(layer));
  return layer;
}

static void window_load(Window *window)
{
  Layer *root = window_get_root_layer(window);
  GRect b = layer_get_bounds(root);
  int w = b.size.w;

  s_time_layer = make_label(root, GRect(0, 0, w, 44), FONT_KEY_BITHAM_42_BOLD, NULL);
  s_status_layer = make_label(root, GRect(0, 44, w, 18), FONT_KEY_GOTHIC_18, "Disconnected");

  s_battery_label_layer = make_label(root, GRect(0, 64, w, 14), FONT_KEY_GOTHIC_14, "BATTERY");
  s_battery_layer = make_label(root, GRect(0, 78, w, 32), FONT_KEY_BITHAM_30_BLACK, "--%");

  s_speed_label_layer = make_label(root, GRect(0, 112, w, 14), FONT_KEY_GOTHIC_14, "SPEED");
  s_speed_layer = make_label(root, GRect(0, 126, w, 32), FONT_KEY_BITHAM_30_BLACK, "-- mph");

  update_time();
  update_stats_display();
}

static void window_unload(Window *window)
{
  text_layer_destroy(s_time_layer);
  text_layer_destroy(s_status_layer);
  text_layer_destroy(s_battery_label_layer);
  text_layer_destroy(s_battery_layer);
  text_layer_destroy(s_speed_label_layer);
  text_layer_destroy(s_speed_layer);
}

static void init(void)
{
  s_window = window_create();
  window_set_background_color(s_window, GColorBlack);
  window_set_window_handlers(s_window, (WindowHandlers){
                                           .load = window_load,
                                           .unload = window_unload,
                                       });
  window_stack_push(s_window, true);

  tick_timer_service_subscribe(MINUTE_UNIT, tick_handler);

  app_message_register_inbox_received(inbox_received_callback);
  app_message_register_inbox_dropped(inbox_dropped_callback);
  app_message_open(128, 64);

#if DEBUG_STUB
  app_timer_register(1000, stub_tick, NULL);
#endif
}

static void deinit(void)
{
  tick_timer_service_unsubscribe();
  window_destroy(s_window);
}

int main(void)
{
  init();
  app_event_loop();
  deinit();
  return 0;
}
