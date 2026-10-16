package com.aeon.prototype;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PERM = 1;
    private static final int REQ_TRASH = 2;

    private static final String COL_ID = MediaStore.Images.Media._ID;
    private static final String COL_NAME = MediaStore.Images.Media.DISPLAY_NAME;
    private static final String COL_DATE = MediaStore.Images.Media.DATE_ADDED;
    private static final String COL_TRASHED = MediaStore.MediaColumns.IS_TRASHED;

    private static final String[] PROJECTION = { COL_ID, COL_NAME, COL_DATE, COL_TRASHED };

    private static final String PERM_PARTIAL = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";

    private final ArrayList<Long> ids = new ArrayList<Long>();

    private TextView statusText;
    private TextView bannerText;
    private Button grantButton;
    private ListView listView;

    private long pendingId = -1L;
    private boolean routeToSettings = false;
    private boolean askedOnce = false;
    private String listError = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = (TextView) findViewById(R.id.status);
        bannerText = (TextView) findViewById(R.id.banner);
        grantButton = (Button) findViewById(R.id.grant);
        listView = (ListView) findViewById(R.id.list);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onItemSelected(position);
            }
        });

        grantButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onGrantButtonClicked();
            }
        });

        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private String mainPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return Manifest.permission.READ_MEDIA_IMAGES;
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private List<String> requiredPermissions() {
        List<String> perms = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 34) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(PERM_PARTIAL);
        } else if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return perms;
    }

    private boolean hasFullAccess() {
        return checkSelfPermission(mainPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasPartialAccess() {
        if (Build.VERSION.SDK_INT >= 34) {
            return checkSelfPermission(PERM_PARTIAL) == PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    private void refreshUi() {
        boolean full = hasFullAccess();
        boolean partial = hasPartialAccess();

        if (full || partial) {
            grantButton.setVisibility(View.GONE);

            if (partial && !full) {
                bannerText.setVisibility(View.VISIBLE);
                bannerText.setText(
                        "Accès partiel aux images : seules les photos que tu as choisies sont "
                                + "visibles pour cette application. La liste ci-dessous peut être incomplète.");
            } else {
                bannerText.setVisibility(View.GONE);
            }

            loadList();
            if (listError != null) {
                statusText.setText(listError);
            } else {
                statusText.setText(ids.isEmpty()
                        ? "Aucune image accessible. Aucune action possible."
                        : "Touche une image pour la sélectionner explicitement.");
            }
        } else {
            bannerText.setVisibility(View.GONE);
            ids.clear();
            listView.setAdapter(new ArrayAdapter<String>(this,
                    android.R.layout.simple_list_item_1, new ArrayList<String>()));

            grantButton.setVisibility(View.VISIBLE);
            if (routeToSettings) {
                grantButton.setText("Ouvrir les réglages de l'application");
                statusText.setText(
                        "Permission refusée définitivement. Le bouton ouvre les réglages Android de "
                                + "l'application : Autorisations, puis Photos et vidéos. "
                                + "Rien n'est listé et aucune action n'est possible tant que l'accès manque.");
            } else {
                grantButton.setText("Accorder la permission");
                statusText.setText(
                        "Permission non accordée. Rien n'est listé, aucune action n'est possible. "
                                + "Touche le bouton pour la demander.");
            }
        }
    }

    private void onGrantButtonClicked() {
        if (routeToSettings) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            } catch (Exception e) {
                statusText.setText("Impossible d'ouvrir les réglages : " + e);
            }
            return;
        }
        String[] perms = requiredPermissions().toArray(new String[0]);
        requestPermissions(perms, REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERM) {
            return;
        }
        askedOnce = true;
        if (hasFullAccess() || hasPartialAccess()) {
            routeToSettings = false;
        } else if (askedOnce && !shouldShowRequestPermissionRationale(mainPermission())) {
            routeToSettings = true;
        }
        refreshUi();
    }

    // Le filtre IS_TRASHED est appliqué côté application : aucune dépendance
    // à la sémantique de la clause de sélection du fournisseur.
    private void loadList() {
        ids.clear();
        listError = null;
        ArrayList<String> rows = new ArrayList<String>();
        try {
            Cursor c = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    PROJECTION,
                    null,
                    null,
                    COL_DATE + " DESC");
            if (c != null) {
                try {
                    int idIdx = c.getColumnIndexOrThrow(COL_ID);
                    int nameIdx = c.getColumnIndexOrThrow(COL_NAME);
                    int dateIdx = c.getColumnIndexOrThrow(COL_DATE);
                    int trashedIdx = c.getColumnIndexOrThrow(COL_TRASHED);
                    while (c.moveToNext() && ids.size() < 200) {
                        if (c.getInt(trashedIdx) != 0) {
                            continue;
                        }
                        long id = c.getLong(idIdx);
                        ids.add(Long.valueOf(id));
                        rows.add(c.getString(nameIdx)
                                + "\n#" + id
                                + "  ·  "
                                + DateFormat.format("dd/MM/yyyy HH:mm:ss", c.getLong(dateIdx) * 1000L));
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception e) {
            listError = "Erreur du gestionnaire de médias pendant la lecture : " + e
                    + ". Liste non actualisée.";
        }
        listView.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, rows));
    }

    private void onItemSelected(final int position) {
        final long id = ids.get(position).longValue();
        Uri uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                String.valueOf(id));

        String name = null;
        long date = 0L;
        int trashed = -1;

        try {
            Cursor c = getContentResolver().query(uri, PROJECTION, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        name = c.getString(c.getColumnIndexOrThrow(COL_NAME));
                        date = c.getLong(c.getColumnIndexOrThrow(COL_DATE));
                        trashed = c.getInt(c.getColumnIndexOrThrow(COL_TRASHED));
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception e) {
            statusText.setText("INDÉTERMINÉ — relecture impossible avant confirmation : " + e
                    + ". Aucune action entreprise. Aucune conclusion sur l'état du fichier.");
            return;
        }

        if (name == null) {
            statusText.setText("INDÉTERMINÉ — le fichier #" + id
                    + " est introuvable au moment de la confirmation "
                    + "(modifié ou supprimé par ailleurs). Aucune action entreprise.");
            loadList();
            return;
        }

        if (trashed != 0) {
            statusText.setText("INCOHÉRENT — le fichier #" + id
                    + " est déjà dans la corbeille alors qu'il figurait dans la liste hors corbeille. "
                    + "Concurrence détectée : aucune action, aucune revendication.");
            loadList();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Cible sélectionnée")
                .setMessage("Fichier : " + name
                        + "\nIdentifiant stable : #" + id
                        + "\nDate d'ajout : " + DateFormat.format("dd/MM/yyyy HH:mm:ss", date * 1000L)
                        + "\nURI : " + uri
                        + "\n\nPlacer CE fichier dans la corbeille ?")
                .setPositiveButton("Mettre à la corbeille", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        trash(id);
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void trash(final long id) {
        pendingId = id;
        Uri uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                String.valueOf(id));
        statusText.setText("Demande envoyée au système — en attente de ta décision…");

        try {
            // [AV] Troisième argument booléen selon la documentation citée :
            // createTrashRequest(ContentResolver, Collection<Uri>, boolean) ;
            // true = placer dans la corbeille. Si le compilateur indique que le
            // troisième paramètre n'est pas un booléen, rapporte le message
            // d'erreur exact : la correction porte sur cette seule instruction.
            PendingIntent pendingIntent = MediaStore.createTrashRequest(
                    getContentResolver(),
                    Collections.singletonList(uri),
                    true);

            if (pendingIntent == null) {
                pendingId = -1L;
                statusText.setText("ÉCHEC CONFIRMÉ — le système n'a pas fourni de demande de mise "
                        + "en corbeille. Aucune opération soumise.");
                return;
            }

            startIntentSenderForResult(pendingIntent.getIntentSender(), REQ_TRASH, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException e) {
            pendingId = -1L;
            statusText.setText("ÉCHEC CONFIRMÉ — envoi de la demande impossible : " + e
                    + ". Aucune opération soumise.");
        } catch (Exception e) {
            pendingId = -1L;
            statusText.setText("ÉCHEC CONFIRMÉ — la demande n'a pas pu être émise : " + e
                    + ". Aucune opération soumise.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TRASH || pendingId < 0L) {
            return;
        }
        long id = pendingId;
        pendingId = -1L;

        if (resultCode == RESULT_CANCELED) {
            statusText.setText("ANNULÉ — tu as refusé la mise en corbeille. "
                    + "Aucune conclusion n'en découle sur l'état du fichier ; liste relue.");
            loadList();
        } else if (resultCode == RESULT_OK) {
            verify(id);
        } else {
            statusText.setText("INDÉTERMINÉ — code de résultat inattendu (" + resultCode
                    + "). Je ne peux ni affirmer la réussite, ni affirmer l'absence de modification.");
        }
    }

    private void verify(long id) {
        Uri uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                String.valueOf(id));
        try {
            Cursor c = getContentResolver().query(uri,
                    new String[]{COL_TRASHED},
                    null, null, null);
            if (c == null) {
                statusText.setText("INDÉTERMINÉ — le système rapporte l'opération terminée, mais la "
                        + "lecture du gestionnaire de médias est impossible pour le fichier #" + id
                        + ". Aucune conclusion sur l'état du fichier.");
            } else {
                try {
                    if (!c.moveToFirst()) {
                        statusText.setText("INDÉTERMINÉ — le système rapporte l'opération terminée, "
                                + "mais la ligne #" + id + " est introuvable. État impossible à vérifier : "
                                + "aucune conclusion.");
                    } else if (c.getInt(0) == 1) {
                        statusText.setText("RÉUSSITE CONFIRMÉE — l'opération que tu as approuvée est "
                                + "terminée, et je constate que le fichier #" + id
                                + " est maintenant dans la corbeille. "
                                + "(Je ne prétends rien de plus : opération achevée et état constaté.)");
                    } else {
                        statusText.setText("INCOHÉRENT — le système rapporte l'opération terminée, mais "
                                + "la relecture montre le fichier hors corbeille. Aucune conclusion "
                                + "satisfaisante : signale ce cas.");
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception e) {
            statusText.setText("INDÉTERMINÉ — la vérification a échoué : " + e
                    + ". Je ne peux ni affirmer la réussite, ni affirmer l'absence de modification.");
        }
        loadList();
    }
}
