package com.dreamstatic.mpcremote;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The INSTALL.txt shipped inside plugin zips — ported verbatim from install_text() in the
 * SDK's package.py so converted packages read exactly like SDK-built ones.
 */
public final class InstallText {

    private InstallText() {
    }

    public static String installText(Map<String, Object> manifest, String folder) {
        String name = String.valueOf(manifest.get("name"));
        String kind = manifest.get("kind") == null ? "" : String.valueOf(manifest.get("kind"));
        Object descriptionObj = manifest.get("description");
        String description = descriptionObj instanceof String s ? s : null;
        Object versionObj = manifest.get("version");
        String version = versionObj == null ? "" : String.valueOf(versionObj);

        List<String> lines = new ArrayList<>();
        lines.add(name + (version.isEmpty() ? "" : " " + version) + " - plugin for Akai MPC standalone");
        lines.add("by " + manifest.get("manufacturer"));
        lines.add("=".repeat(72));
        lines.add("");
        if (description != null && !description.strip().isEmpty()) {
            lines.add(description.strip());
            lines.add("");
        }
        if (kind.equals("instrument")) {
            lines.add("This is an INSTRUMENT plugin (it makes sound and is played from the pads / keys / sequencer).");
        } else if (kind.equals("effect")) {
            lines.add("This is an EFFECT plugin (it processes audio as an insert on a track or program).");
        }
        lines.add("");
        lines.add("HOW TO INSTALL");
        lines.add("--------------");
        lines.add("");
        lines.add("1. Copy the folder");
        lines.add("");
        lines.add("       \"" + folder + "\"");
        lines.add("");
        lines.add("   into a folder named exactly   VST2 Plugins   at the root (top level) of a USB drive,");
        lines.add("   an SD card, or the MPC's internal drive. Create the \"VST2 Plugins\" folder if it does not");
        lines.add("   exist yet. The result should look like this:");
        lines.add("");
        lines.add("       <drive>/VST2 Plugins/" + folder + "/plugin.json");
        lines.add("       <drive>/VST2 Plugins/" + folder + "/version.xml");
        lines.add("       <drive>/VST2 Plugins/" + folder + "/VST/...");
        lines.add("");
        lines.add("   Keep the folder name exactly as it is - the MPC uses it to find the plugin.");
        lines.add("");
        lines.add("2. On the MPC, open   Preferences > Enhancements (Beta)   and switch on both");
        lines.add("   \"Enable Enhancements\" and \"External Plugin Support\". Plug in the drive (or restart the MPC if the");
        lines.add("   plugin does not appear).");
        lines.add("");
        lines.add("3. The plugin now appears in the plugin browser under   \"External Plugins\".");
        if (kind.equals("instrument")) {
            lines.add("   Create a Plugin program and pick it from the list - then play it from the pads or a keyboard.");
        } else if (kind.equals("effect")) {
            lines.add("   Add it as an insert effect on a track, program, pad or mixer channel.");
        } else {
            lines.add("   Instruments appear when you create a Plugin program; effects appear as insert effects.");
        }
        lines.add("");
        lines.add("TO UNINSTALL");
        lines.add("------------");
        lines.add("");
        lines.add("Delete the \"" + folder + "\" folder from \"VST2 Plugins\". Projects that used the plugin will");
        lines.add("still open, but the plugin's sound/effect will be missing.");
        lines.add("");
        lines.add("PLEASE READ - IMPORTANT");
        lines.add("-----------------------");
        lines.add("");
        lines.add("* Third-party plugins are NOT supported by inMusic / Akai. If something goes wrong, inMusic");
        lines.add("  support cannot help with it.");
        lines.add("* A plugin runs inside the MPC software. A faulty plugin can make the MPC freeze or crash.");
        lines.add("  SAVE YOUR WORK OFTEN, and do not rely on third-party plugins for a live show until you");
        lines.add("  have tested them thoroughly.");
        lines.add("* Requires MPC OS 3.10.1 or later. Older MPC OS versions ignore this folder entirely;");
        lines.add("  update the MPC first (Preferences > Info shows the version).");
        lines.add("* External plugins must be enabled on the device: Preferences > Enhancements (Beta) >");
        lines.add("  Enable Enhancements and External Plugin Support. Until both are on, the plugin stays hidden.");
        lines.add("* If the MPC crashes or the plugin never appears, remove the folder from \"VST2 Plugins\"");
        lines.add("  and restart the MPC.");
        lines.add("");
        Object urlObj = manifest.get("url");
        if (urlObj instanceof String url && !url.strip().isEmpty()) {
            lines.add("More information: " + url.strip());
            lines.add("");
        }
        return String.join("\n", lines) + "\n";
    }
}
