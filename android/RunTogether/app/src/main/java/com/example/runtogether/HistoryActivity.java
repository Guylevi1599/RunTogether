package com.example.runtogether;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.Request;
import com.android.volley.toolbox.JsonArrayRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * HistoryActivity
 *
 * Purpose:
 *  • Fetch and display the user's run history from the Node backend.
 *  • Sort runs by date (newest first), render a per-run summary row, and compute
 *    top-level averages (speed, distance, heart rate).
 */
public class HistoryActivity extends AppCompatActivity {

    private ListView listRuns;
    private static final String BASE_URL = "http://10.0.2.2:3000/api/history/";

    // Top statistics views
    private TextView textAvgSpeed, textAvgDistance, textAvgHeartRate;

    // Input & output date formats (server → display)
    private static final SimpleDateFormat INPUT_DATE_FMT  =
            new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private static final SimpleDateFormat OUTPUT_DATE_FMT =
            new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        bindViews();
        loadHistory();
    }

    /** Bind layout views. */
    private void bindViews() {
        listRuns = findViewById(R.id.listRuns);
        textAvgSpeed = findViewById(R.id.textAvgSpeed);
        textAvgDistance = findViewById(R.id.textAvgDistance);
        textAvgHeartRate = findViewById(R.id.textAvgHeartRate);
    }

    /** Load user's run history and update UI. */
    private void loadHistory() {
        SharedPreferences prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE);
        int userId = prefs.getInt("userId", -1);

        if (userId == -1) {
            Toast.makeText(this, "שגיאה: משתמש לא מחובר", Toast.LENGTH_SHORT).show();
            return;
        }

        String url = BASE_URL + userId;
        Log.d("HISTORY", "User ID: " + userId);
        Log.d("HISTORY", "Request URL: " + url);

        JsonArrayRequest request = new JsonArrayRequest(
                Request.Method.GET,
                url,
                null,
                response -> handleHistoryResponse(response),
                error -> {
                    Log.e("HISTORY", "שגיאה בטעינת הריצות", error);
                    Toast.makeText(this, "שגיאה בטעינת הריצות", Toast.LENGTH_SHORT).show();
                }
        );

        // Use the shared RequestQueue (no duplicates/leaks).
        VolleySingleton.getInstance(this).getRequestQueue().add(request);
    }

    /** Handle the JSON array of runs returned by the server. */
    private void handleHistoryResponse(JSONArray response) {
        ArrayList<String> runSummaries = new ArrayList<>();
        ArrayList<JSONObject> runsList = new ArrayList<>();

        double totalSpeed = 0.0, totalDistance = 0.0, totalHeartRate = 0.0;

        try {
            // Build a mutable list for sorting
            for (int i = 0; i < response.length(); i++) {
                runsList.add(response.getJSONObject(i));
            }

            // Sort newest-first by run_date (server sends "yyyy-MM-dd")
            runsList.sort((o1, o2) -> {
                String s1 = o1.optString("run_date", "");
                String s2 = o2.optString("run_date", "");
                Date d1 = parseDateSafe(INPUT_DATE_FMT, s1);
                Date d2 = parseDateSafe(INPUT_DATE_FMT, s2);
                if (d1 != null && d2 != null) {
                    return Long.compare(d2.getTime(), d1.getTime()); // newer first
                }
                // Fallback to lexical compare if dates are malformed
                return s2.compareTo(s1);
            });

            // Build row summaries and accumulate stats
            for (JSONObject run : runsList) {
                String dateRaw = run.optString("run_date", "לא ידוע");
                double timeMin  = run.optDouble("total_time_minutes", 0);
                double hrAvg    = run.optDouble("average_heart_rate", 0);
                double speedKmh = run.optDouble("average_speed_kmh", 0);
                double distKm   = run.optDouble("total_distance_km", 0);

                // Format date for display (to "dd.MM.yyyy"); fallback to the raw string if parse fails
                Date parsed = parseDateSafe(INPUT_DATE_FMT, dateRaw);
                String dateDisp = (parsed != null) ? OUTPUT_DATE_FMT.format(parsed) : dateRaw;

                totalSpeed    += speedKmh;
                totalDistance += distKm;
                totalHeartRate += hrAvg;

                String summary =
                        "📅 תאריך: " + dateDisp +
                                "\n⏱️ זמן: " + String.format(Locale.getDefault(), "%.0f", timeMin) + " דק" +
                                "\n💓 דופק ממוצע: " + String.format(Locale.getDefault(), "%.2f", hrAvg) +
                                "\n🚀 מהירות: " + String.format(Locale.getDefault(), "%.2f", speedKmh) + " קמ\"ש" +
                                "\n📏 מרחק: " + String.format(Locale.getDefault(), "%.2f", distKm) + " ק\"מ";

                runSummaries.add(summary);
            }

            // Update top cards with averages (if any runs exist)
            int runCount = runsList.size();
            if (runCount > 0) {
                double avgSpeed = totalSpeed / runCount;
                double avgDistance = totalDistance / runCount;
                double avgHeartRate = totalHeartRate / runCount;

                textAvgSpeed.setText(String.format(Locale.getDefault(), "%.2f\nקמ\"ש", avgSpeed));
                textAvgDistance.setText(String.format(Locale.getDefault(), "%.2f\nק\"מ", avgDistance));
                textAvgHeartRate.setText(String.format(Locale.getDefault(), "%.2f\nBPM", avgHeartRate));
            } else {
                Toast.makeText(this, "לא נמצאו ריצות עבור המשתמש", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Log.e("HISTORY", "שגיאה בעיבוד JSON", e);
        }

        // Bind the list to the adapter (simple text row layout)
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                R.layout.list_item_run,
                R.id.textRun,
                runSummaries
        );
        listRuns.setAdapter(adapter);
    }

    /** Safe date parsing helper (returns null on parse error). */
    private Date parseDateSafe(SimpleDateFormat fmt, String s) {
        try {
            return fmt.parse(s);
        } catch (ParseException e) {
            return null;
        }
    }
}
