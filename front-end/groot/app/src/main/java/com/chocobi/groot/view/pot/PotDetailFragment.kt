package com.chocobi.groot.view.pot

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import com.chocobi.groot.MainActivity
import com.chocobi.groot.R
import com.chocobi.groot.data.RetrofitClient
import com.chocobi.groot.view.pot.model.Plant
import com.chocobi.groot.view.pot.model.Pot
import com.chocobi.groot.view.pot.model.PotResponse
import com.chocobi.groot.view.pot.model.PotService
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.utils.Color
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response


@Suppress("DEPRECATION")
class PotDetailFragment : Fragment() {

    private val TAG = "PotDetailFragment"
    private var pot: Pot? = null
    private var plant: Plant? = null
    private lateinit var characterSceneView: SceneView
    private lateinit var potNameText: TextView
    private lateinit var potPlantText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
        }

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        getPotDetail(24)
        var rootView = inflater.inflate(R.layout.fragment_pot_detail, container, false)
        val mActivity = activity as MainActivity
        val potId = arguments?.getInt("potId")
        characterSceneView = rootView.findViewById<SceneView>(R.id.characterSceneView)

        Log.d(TAG, "${pot}")
        potNameText = rootView.findViewById(R.id.potName)
        potPlantText = rootView.findViewById(R.id.potPlant)


//        다이어리 버튼 클릭시
        val potPostDiaryBtn = rootView.findViewById<ImageButton>(R.id.potPostDiaryBtn)
        potPostDiaryBtn.setOnClickListener {
            if (potId is Int) {
                mActivity.setPotId(potId)
            }
            mActivity.changeFragment("pot_diary_create")
        }
        // Inflate the layout for this fragment
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


//        탭 조작
        val tab1 = PotDetailTab1Fragment()
        val tab2 = PotDetailTab2Fragment()
        val tab3 = PotDetailTab3Fragment()
        val tab4 = PotDetailTab4Fragment()
        val tab5 = PotDetailTab5Fragment()

        var tabBtn1 = view.findViewById<ImageButton>(R.id.tabBtn1)
        var tabBtn2 = view.findViewById<ImageButton>(R.id.tabBtn2)
        var tabBtn3 = view.findViewById<ImageButton>(R.id.tabBtn3)
        var tabBtn4 = view.findViewById<ImageButton>(R.id.tabBtn4)
        var tabBtn5 = view.findViewById<ImageButton>(R.id.tabBtn5)
        tabBtn1.setOnClickListener {
            childFragmentManager.beginTransaction().replace(R.id.tab_container, tab1).commit()
        }
        tabBtn2.setOnClickListener {
            childFragmentManager.beginTransaction().replace(R.id.tab_container, tab2).commit()
        }
        tabBtn3.setOnClickListener {
            childFragmentManager.beginTransaction().replace(R.id.tab_container, tab3).commit()
        }
        tabBtn4.setOnClickListener {
            childFragmentManager.beginTransaction().replace(R.id.tab_container, tab4).commit()
        }
        tabBtn5.setOnClickListener {
            childFragmentManager.beginTransaction().replace(R.id.tab_container, tab5).commit()
        }
    }

    fun getPotDetail(potId: Int) {
        var retrofit = RetrofitClient.getClient()!!
        var potService = retrofit.create(PotService::class.java)
        potService.getPotDetail(potId).enqueue(object :
            Callback<PotResponse> {
            override fun onResponse(
                call: Call<PotResponse>,
                response: Response<PotResponse>
            ) {
                val body = response.body()
                if (body != null && response.code() == 200) {
                    Log.d(TAG, "$body")
                    Log.d(TAG, "body: $body")
                    pot = body.pot
                    Log.d(TAG, "pot: $pot")
                    plant = body.plant
                    Log.d(TAG, "plant: $plant")
                    setCharacterSceneView()
                    setPlantContent()
                } else {
                    Log.d(TAG, "실패1")
                }
            }

            override fun onFailure(call: Call<PotResponse>, t: Throwable) {
                Log.d(TAG, "실패2")
            }
        })
    }

    fun setCharacterSceneView() {
        characterSceneView.backgroundColor = Color(255.0f, 255.0f, 255.0f, 255.0f)

        val modelNode = ModelNode().apply {
            loadModelGlbAsync(
                glbFileLocation = pot?.characterGLBPath
                    ?: "https://groot-a303-s3.s3.ap-northeast-2.amazonaws.com/assets/unicorn_2.glb",
                autoAnimate = false,
                scaleToUnits = 1.0f,
                centerOrigin = Position(x = 0f, y = 0f, z = 0f),
            )
        }
        characterSceneView.addChild(modelNode)
    }

    fun setPlantContent() {
        potNameText.text = pot?.potName
        potPlantText.text = pot?.plantKrName
    }

}