package crosby.removetags;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

import org.openstreetmap.osmosis.core.filter.v0_6.TagFilter;
import org.openstreetmap.osmosis.core.pipeline.common.TaskConfiguration;
import org.openstreetmap.osmosis.core.pipeline.common.TaskManager;
import org.openstreetmap.osmosis.core.pipeline.common.TaskManagerFactory;
import org.openstreetmap.osmosis.core.pipeline.v0_6.SinkManager;
import org.openstreetmap.osmosis.core.pipeline.v0_6.SinkSourceManager;

import crosby.binary.file.BlockOutputStream;
import crosby.counttags.CountTags;



public class RemoveTagsFactory extends TaskManagerFactory {
		@Override
		protected TaskManager createTaskManagerImpl(TaskConfiguration taskConfig) {
			String tagstring = this.getStringArgument(taskConfig,"tags");
			Set<String> tags = new HashSet<String>();
			for (String i : tagstring.split(",",0)) {
				System.out.println("To remove tag: '"+i+"'");
				tags.add(i);
			}
			RemoveTags task = new RemoveTags(tags);
			return new SinkSourceManager(taskConfig.getId(), task, taskConfig.getPipeArgs());		
		}
	}
