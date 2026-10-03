package toomanyagents.agent.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded reading views. Tool payloads are accessed only for an explicitly opened row. */
final class ConversationTranscript {
    private static final int PAGE = 24, TEXT_PAGE = 8000;

    static JsonObject page(Map<String, JsonObject> rows, java.util.Set<String> openTurns, JsonObject query) {
        var lastReply = new LinkedHashMap<String, String>();
        for (var row : rows.values()) if (visible(row) && text(row,"role").equals("assistant"))
            lastReply.put(text(row,"turnId"), text(row,"id"));
        var roots = new ArrayList<JsonObject>();
        var groups = new LinkedHashMap<String,List<JsonObject>>();
        var activeGroups = new LinkedHashMap<String,JsonObject>();
        JsonObject group = null, recentWork = null;
        for (var row : rows.values()) {
            if (!visible(row)) continue;
            String id=text(row,"id"), turn=text(row,"turnId"), role=text(row,"role");
            if (role.equals("user")) recentWork = null;
            // Thread-level notices belong in nearby work details without changing their saved scope.
            if (turn.isBlank() && recentWork != null && List.of("warning", "error", "unsupported").contains(text(row,"kind"))) {
                var members=groups.get(text(recentWork,"id"));members.add(row);
                recentWork.addProperty("count",members.size());
                continue;
            }
            if (role.equals("user") || id.equals(lastReply.get(turn)) || turn.isBlank()) {
                roots.add(row); group=null;
            } else {
                if (group==null || !text(group,"turnId").equals(turn)) {
                    group=new JsonObject();group.addProperty("id","work:"+id);group.addProperty("kind","work");
                    group.addProperty("turnId",turn);group.add("createdAt",row.get("createdAt"));
                    roots.add(group);groups.put(text(group,"id"),new ArrayList<>());
                }
                recentWork=group;
                var members=groups.get(text(group,"id"));members.add(row);
                group.addProperty("count",members.size());
                group.addProperty("updatedAt",Math.max(number(group,"updatedAt"),number(row,"updatedAt")));
                if (openTurns.contains(turn)) activeGroups.put(turn,group);
                if (text(row,"status").equals("failed") || text(row,"kind").equals("error")) group.addProperty("failed",true);
            }
        }
        // Only the latest activity group of an open turn is still working.
        for(var active:activeGroups.values()) active.addProperty("running",true);
        int end=roots.size();String before=text(query,"before");
        if (!before.isBlank()) for(int i=0;i<roots.size();i++) if(text(roots.get(i),"id").equals(before)) {end=i;break;}
        int start=Math.max(0,end-PAGE);
        var result=new JsonObject();var entries=new JsonArray();
        for(int i=start;i<end;i++) { var root=roots.get(i);entries.add(text(root,"kind").equals("work")?root:summary(root,4000)); }
        result.add("entries",entries);result.addProperty("hasOlder",start>0);
        result.addProperty("firstId",start<end?text(roots.get(start),"id"):"");
        String lastReplyId="";
        for(var row:rows.values())if(visible(row)&&text(row,"role").equals("assistant"))lastReplyId=text(row,"id");
        result.addProperty("lastReplyId",lastReplyId);
        String selected=text(query,"group");var members=groups.get(selected);
        if(members!=null) {
            int offset=Math.clamp((int)number(query,"groupOffset"),0,Math.max(0,members.size()-1));
            var expanded=new JsonObject();var items=new JsonArray();
            for(int i=offset;i<Math.min(members.size(),offset+PAGE);i++)items.add(summary(members.get(i),1000));
            expanded.addProperty("id",selected);expanded.addProperty("offset",offset);expanded.addProperty("total",members.size());
            expanded.addProperty("hasMore",offset+PAGE<members.size());expanded.add("entries",items);result.add("group",expanded);
        }
        JsonObject row=rows.get(text(query,"row"));
        if(row!=null && visible(row)) {
            String content=detail(row);
            int offset=Math.clamp((int)number(query,"textOffset"),0,Math.max(0,content.length()-1));
            int limit=Math.min(content.length(),offset+TEXT_PAGE);
            var detail=new JsonObject();detail.addProperty("id",text(row,"id"));
            detail.addProperty("text",content.substring(offset,limit));detail.addProperty("offset",offset);
            detail.addProperty("total",content.length());detail.addProperty("hasMore",limit<content.length());
            result.add("detail",detail);
        }
        return result;
    }

    private static JsonObject summary(JsonObject row,int limit) {
        var value=new JsonObject();
        for(String key:List.of("id","kind","role","status","createdAt","updatedAt","turnId"))
            if(row.has(key))value.add(key,row.get(key));
        String title=text(row,"label");if(title.isBlank())title=text(row,"title");
        value.addProperty("title",clip(title.replace('\n',' '),160));
        String role=text(row,"role");
        if(role.equals("user")||role.equals("assistant")) {
            String body=text(row,"text");value.addProperty("text",clip(body,limit));value.addProperty("truncated",body.length()>limit);
            if(text(row,"status").equals("rejected"))value.addProperty("detail",clip(text(row,"detail"),512));
            if(row.has("media")) {
                value.addProperty("attachments",row.getAsJsonArray("media").size());
                int images=0;
                for(var media:row.getAsJsonArray("media")) {
                    String type=text(media.getAsJsonObject(),"type");
                    if(type.equals("image") || type.equals("localImage"))images++;
                }
                value.addProperty("images",images);
            }
        }
        return value;
    }

    private static String detail(JsonObject row) {
        var body=new StringBuilder();var item=row.has("item")?row.getAsJsonObject("item"):new JsonObject();
        String title=text(row,"title");if(!title.isBlank())body.append(title).append('\n');
        if(item.has("arguments"))body.append("\nInput\n").append(item.get("arguments")).append('\n');
        if(row.has("cwd"))body.append("Directory: ").append(text(row,"cwd")).append('\n');
        if(row.has("exitCode"))body.append("Exit: ").append(row.get("exitCode")).append('\n');
        body.append('\n').append(text(row,"text"));
        String extra=text(row,"detail");if(!extra.isBlank())body.append('\n').append(extra);
        if(row.has("output"))body.append('\n').append(text(row,"output"));
        if(row.has("interaction"))body.append('\n').append(row.get("interaction"));
        if(row.has("media"))for(var element:row.getAsJsonArray("media")) {
            var media=element.getAsJsonObject();String ref=text(media,"reference");
            body.append('\n').append(text(media,"type")).append(": ").append(ref.startsWith("data:")?"Saved inline content":ref);
        }
        return body.toString().strip();
    }
    private static boolean visible(JsonObject r) {
        return !r.has("parentToolCallId") && !flag(r,"suppressed") && !flag(r,"skipTranscript")
            && !(List.of("reasoning","agentMessage","plan").contains(text(r,"kind"))&&text(r,"text").isBlank());
    }
    private static String clip(String s,int n) {return s.length()>n?s.substring(0,n)+"…":s;}
    private static String text(JsonObject o,String k) {return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static long number(JsonObject o,String k) {return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsLong():0;}
    private static boolean flag(JsonObject o,String k) {return o.has(k)&&o.get(k).getAsBoolean();}
}
