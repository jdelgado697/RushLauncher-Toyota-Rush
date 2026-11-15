/* Rush Launcher 3.7 — grupos en el cajón de apps.
 * Clasificación local, sin permisos ni consultas externas.
 * Revisión de packageId antes que nombre para evitar falsos positivos.
 */
(function (global) {
  'use strict';
  var videoPackages = {
    'com.google.android.youtube':true,
    'com.google.android.apps.youtube.tv':true,
    'com.google.android.videos':true,
    'com.netflix.mediaclient':true,
    'com.amazon.avod.thirdpartyclient':true,
    'com.amazon.avod':true,
    'com.disney.disneyplus':true,
    'com.hbo.hbonow':true,
    'com.wbd.stream':true,
    'com.hbo.max':true,
    'com.cbs.app':true,
    'com.paramountplus':true,
    'com.pluto.tv':true,
    'com.tubitv':true,
    'com.crunchyroll.crunchyroid':true,
    'tv.twitch.android.app':true,
    'com.plexapp.android':true,
    'org.videolan.vlc':true,
    'org.xbmc.kodi':true,
    'com.mxtech.videoplayer.ad':true,
    'com.mxtech.videoplayer.pro':true,
    'com.stremio.one':true,
    'com.stremio.android':true,
    'com.directv.dtvla':true,
    'com.clarovideo.android':true
  };
  var audioPackages = {
    'com.spotify.music':true,
    'com.google.android.apps.youtube.music':true,
    'com.amazon.mp3':true,
    'com.apple.android.music':true,
    'deezer.android.app':true,
    'com.aspiro.tidal':true,
    'com.soundcloud.android':true,
    'com.pandora.android':true,
    'com.maxmpz.audioplayer':true,
    'com.aimp.player':true,
    'com.google.android.music':true
  };
  function fold(v) {
    return String(v || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase();
  }
  function group(item) {
    var pkg = String(item && item.package || '').toLowerCase();
    var name = fold(item && item.name);
    if (videoPackages[pkg]) return 'video';
    if (audioPackages[pkg]) return 'audio';
    // Variantes del nombre del paquete en marcas y versiones de Android TV.
    if (/(^|\.)(netflix|youtube|paramount|disneyplus|crunchyroll|plutotv|tubitv|stremio|videolan|kodi|iptv|hbo|maxstream|plexapp)(\.|$)/.test(pkg)
        && !/music|kids|studio/.test(pkg)) return 'video';
    if (/(^|\.)(spotify|tidal|deezer|soundcloud|audioplayer|ytmusic)(\.|$)/.test(pkg)) return 'audio';

    if (/^(youtube music|yt music|spotify|spotify lite|deezer|tidal|apple music|amazon music|soundcloud|poweramp|aimp|reproductor de musica|music player)(\b|$)/.test(name))
      return 'audio';
    if (/^(youtube|netflix|paramount(\+| plus)?|disney(\+| plus)?|prime video|amazon prime video|hbo max|max|pluto tv|tubi|crunchyroll|twitch|plex|kodi|vlc|mx player|stremio|apple tv|iptv|directv go|dgo|claro video|clarovideo|movistar plus|vix|videos|reproductor de video)(\b|\+|$)/.test(name))
      return 'video';
    return 'other';
  }

  // Etiquetas creadas para personas; títulos de cada sección.
  var sections = [
    {id:'video', title:'Streaming y video'},
    {id:'audio', title:'Música y audio'},
    {id:'other', title:'Otras aplicaciones'}
  ];
  function compareNames(a,b) {
    return String(a.name || '').localeCompare(String(b.name || ''), 'es', {sensitivity:'base'});
  }
  function categorize(items) {
    var result = {video:[],audio:[],other:[]};
    (items || []).forEach(function(item) { result[group(item)].push(item); });
    sections.forEach(function(section) { result[section.id].sort(compareNames); });
    return result;
  }
  global.RushAppCategories = {group:group, categorize:categorize, sections:sections};
})(window);
